package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Phase <b>Monitor</b> de la boucle MAPE-K : serveur socket TCP qui reçoit les
 * {@link MesureSante} envoyées par les sondes (JSON, une mesure par ligne), les valide et
 * met à jour la {@link KnowledgeBase}.
 *
 * <p>Le Monitor <b>ne décide d'aucune action corrective</b> : il se contente de tenir la KB à
 * jour (état courant + historique). Les mesures invalides sont rejetées et comptées.</p>
 *
 * <p>Chaque connexion est traitée par un pool de threads, ce qui permet aux trois sondes
 * d'envoyer en parallèle. Une connexion peut transporter une ou plusieurs lignes.</p>
 */
public class Monitor {

    /** Délai d'inactivité après lequel une connexion de sonde est fermée (ms). */
    public static final int DELAI_LECTURE_MS = 30_000;
    /** Taille maximale acceptée pour une ligne JSON (caractères). */
    public static final int TAILLE_MAX_LIGNE = 8_192;
    /** Tolérance sur un horodatage dans le futur (horloges locales, ms). */
    public static final long TOLERANCE_FUTUR_MS = 60_000;

    private static final Logger LOG = Journal.logger("Monitor");

    private final KnowledgeBase kb;
    private final ExecutorService pool;
    private final AtomicLong nbRecues = new AtomicLong();
    private final AtomicLong nbRejetees = new AtomicLong();
    private final CountDownLatch pret = new CountDownLatch(1);
    private volatile ServerSocket server;
    private volatile boolean actif = false;

    /**
     * @param kb base de connaissances à alimenter
     */
    public Monitor(KnowledgeBase kb) {
        this.kb = Objects.requireNonNull(kb, "kb");
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "monitor-conn-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Écoute sur le port donné et traite les connexions des sondes. <b>Bloquant</b> : à lancer
     * dans un thread dédié, ex. {@code new Thread(() -> monitor.demarrer(9090)).start()}.
     *
     * @param port port TCP d'écoute (9090 par convention ; 0 = port libre choisi par l'OS)
     * @throws UncheckedIOException si le port ne peut pas être ouvert
     */
    public void demarrer(int port) {
        try {
            server = new ServerSocket(port);
        } catch (IOException e) {
            pret.countDown();
            throw new UncheckedIOException("Monitor : impossible d'écouter sur le port " + port, e);
        }
        actif = true;
        pret.countDown();
        LOG.info("Monitor à l'écoute des sondes sur le port " + server.getLocalPort());
        while (actif) {
            try {
                Socket s = server.accept();
                pool.execute(() -> traiterConnexion(s));
            } catch (SocketException e) {
                if (actif) {
                    LOG.warning("Monitor : erreur du socket serveur (" + e.getMessage() + ")");
                }
                break; // socket fermé par arreter()
            } catch (IOException e) {
                LOG.warning("Monitor : connexion refusée (" + e.getMessage() + ")");
            }
        }
        LOG.info("Monitor arrêté");
    }

    /**
     * Démarre le Monitor dans un thread démon et attend qu'il soit à l'écoute.
     *
     * @param port port TCP d'écoute (0 = port libre)
     * @return le thread du Monitor
     * @throws UncheckedIOException si le port ne peut pas être ouvert
     */
    public Thread demarrerEnArrierePlan(int port) {
        Thread t = new Thread(() -> {
            try {
                demarrer(port);
            } catch (UncheckedIOException e) {
                LOG.severe(e.getMessage() + " : " + e.getCause().getMessage());
            }
        }, "monitor");
        t.setDaemon(true);
        t.start();
        try {
            pret.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!actif) {
            throw new UncheckedIOException(new IOException("Monitor non démarré sur le port " + port));
        }
        return t;
    }

    /** Arrête l'écoute et le pool de threads. */
    public void arreter() {
        actif = false;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                LOG.fine(() -> "Fermeture du socket serveur : " + e.getMessage());
            }
        }
        pool.shutdownNow();
    }

    /** Lit toutes les lignes d'une connexion de sonde. */
    private void traiterConnexion(Socket s) {
        try (s; BufferedReader in = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            s.setSoTimeout(DELAI_LECTURE_MS);
            String ligne;
            while ((ligne = in.readLine()) != null) {
                if (!ligne.isBlank()) {
                    traiterLigne(ligne);
                }
            }
        } catch (SocketTimeoutException e) {
            LOG.fine("Connexion inactive fermée");
        } catch (IOException e) {
            LOG.warning("Monitor : erreur de lecture (" + e.getMessage() + ")");
        }
    }

    /**
     * Traite une ligne JSON : désérialisation, validation, mise à jour de la KB.
     *
     * @param json une mesure JSON
     * @return {@code true} si la mesure a été acceptée et stockée
     */
    public boolean traiterLigne(String json) {
        if (json.length() > TAILLE_MAX_LIGNE) {
            rejeter("ligne trop longue (" + json.length() + " caractères)");
            return false;
        }
        MesureSante m;
        try {
            m = MesureSante.depuisJson(json);
        } catch (JsonParseException | IllegalStateException e) {
            rejeter("JSON invalide (" + e.getMessage() + ")");
            return false;
        }
        String erreur = valider(m);
        if (erreur != null) {
            rejeter(erreur + " — " + json);
            return false;
        }
        MesureSante precedente = kb.getMesure(m.service).orElse(null);
        kb.majMesure(m.service, m);
        nbRecues.incrementAndGet();
        if (precedente != null && !precedente.statut.equals(m.statut)) {
            LOG.warning("[M] " + m.service + " change d'état : " + precedente.statut + " → " + m.statut);
        }
        LOG.info("[M] Mesure reçue : " + m);
        return true;
    }

    /**
     * Vérifie qu'une mesure respecte le format convenu.
     *
     * @param m mesure à vérifier
     * @return {@code null} si la mesure est valide, sinon la description du problème
     */
    public static String valider(MesureSante m) {
        if (m == null) {
            return "mesure absente";
        }
        if (m.service == null || m.service.isBlank()) {
            return "champ 'service' manquant";
        }
        if (m.statut == null || !MesureSante.STATUTS.contains(m.statut)) {
            return "statut invalide '" + m.statut + "' (attendu SERVING, DEGRADED ou DOWN)";
        }
        if (m.timestamp <= 0) {
            return "timestamp manquant ou invalide";
        }
        if (m.timestamp > System.currentTimeMillis() + TOLERANCE_FUTUR_MS) {
            return "timestamp dans le futur";
        }
        if (m.latenceMs < 0) {
            return "latenceMs négative";
        }
        if (Double.isNaN(m.cpuLoad) || m.cpuLoad < 0 || m.cpuLoad > 1) {
            return "cpuLoad hors de [0, 1]";
        }
        if (m.uptimeMs < 0) {
            return "uptimeMs négatif";
        }
        return null;
    }

    private void rejeter(String raison) {
        nbRejetees.incrementAndGet();
        LOG.warning("[M] Mesure rejetée : " + raison);
    }

    /** @return nombre de mesures acceptées depuis le démarrage */
    public long getNbRecues() {
        return nbRecues.get();
    }

    /** @return nombre de mesures rejetées depuis le démarrage */
    public long getNbRejetees() {
        return nbRejetees.get();
    }

    /** @return port d'écoute réel, ou -1 si le Monitor n'est pas démarré */
    public int getPort() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    /** @return {@code true} si le Monitor est à l'écoute */
    public boolean estActif() {
        return actif;
    }
}
