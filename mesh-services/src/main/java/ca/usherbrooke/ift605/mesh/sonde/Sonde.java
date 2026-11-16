package ca.usherbrooke.ift605.mesh.sonde;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Sonde de supervision d'un service (un thread par service).
 *
 * <p>Toutes les {@value #PERIODE_MS} ms, la sonde :</p>
 * <ol>
 *   <li>appelle le {@code HealthCheck} gRPC du service et mesure la latence aller-retour
 *       ({@link #pinger()}) ;</li>
 *   <li>transmet la {@link MesureSante} en JSON (une ligne) au Monitor par socket TCP
 *       ({@link #envoyerIPC(MesureSante)}).</li>
 * </ol>
 *
 * <p><b>Robustesse :</b> si l'appel gRPC échoue (service arrêté, délai dépassé…), la sonde
 * produit une mesure {@code DOWN} au lieu de planter. Si le Monitor est injoignable, la mesure
 * est perdue (avertissement journalisé) et la sonde continue au cycle suivant.</p>
 */
public class Sonde implements Runnable {

    /** Période entre deux mesures (ms). */
    public static final long PERIODE_MS = 5_000;
    /** Délai maximal accordé au HealthCheck gRPC avant de considérer le service DOWN (ms). */
    public static final long DELAI_GRPC_MS = 2_000;
    /** Délai maximal de connexion au Monitor (ms). */
    public static final int DELAI_CONNEXION_IPC_MS = 1_000;

    private static final Logger LOG = Journal.logger("Sonde");

    private final String service;
    private final ServiceMesh type;
    private final String hoteService;
    private final int grpcPort;
    private final String hoteIpc;
    private final int ipcPort;
    private final ManagedChannel canal;
    private final MethodDescriptor<HealthReq, HealthResp> methodeHealth;

    private volatile boolean active = true;
    private volatile Thread thread;
    private volatile String dernierStatut = null;
    private volatile boolean monitorJoignable = true;

    /**
     * Sonde locale (service et Monitor sur {@code localhost}), comme dans le squelette.
     *
     * @param service  nom du service ({@code CalcService}, {@code StorageService}, {@code NotifService})
     * @param grpcPort port du service à surveiller
     * @param ipcPort  port du Monitor
     */
    public Sonde(String service, int grpcPort, int ipcPort) {
        this(service, ServiceMesh.HOTE_DEFAUT, grpcPort, ServiceMesh.HOTE_DEFAUT, ipcPort);
    }

    /**
     * Constructeur complet.
     *
     * @param service     nom (ou nom d'instance, ex. {@code CalcService#50061}) ; utilisé comme clé
     *                    dans la KnowledgeBase
     * @param hoteService hôte du service
     * @param grpcPort    port gRPC du service
     * @param hoteIpc     hôte du Monitor
     * @param ipcPort     port du Monitor
     * @throws IllegalArgumentException si le nom ne correspond à aucun service connu
     */
    public Sonde(String service, String hoteService, int grpcPort, String hoteIpc, int ipcPort) {
        this.service = service;
        this.type = ServiceMesh.parNom(service);
        this.hoteService = hoteService;
        this.grpcPort = grpcPort;
        this.hoteIpc = hoteIpc;
        this.ipcPort = ipcPort;
        this.methodeHealth = switch (type) {
            case CALC -> CalcServiceGrpc.getHealthCheckMethod();
            case STORAGE -> StorageServiceGrpc.getHealthCheckMethod();
            case NOTIF -> NotifServiceGrpc.getHealthCheckMethod();
        };
        this.canal = ManagedChannelBuilder.forAddress(hoteService, grpcPort).usePlaintext().build();
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        LOG.info(() -> "Sonde " + service + " démarrée (gRPC " + hoteService + ":" + grpcPort
                + " → Monitor " + hoteIpc + ":" + ipcPort + ", période " + PERIODE_MS / 1000 + " s)");
        try {
            while (active && !Thread.currentThread().isInterrupted()) {
                long debut = System.currentTimeMillis();
                MesureSante m = pinger();          // appel gRPC HealthCheck
                envoyerIPC(m);                     // rapport via socket
                long reste = PERIODE_MS - (System.currentTimeMillis() - debut);
                if (reste > 0) {
                    Thread.sleep(reste);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            fermerCanal(canal);
            LOG.info(() -> "Sonde " + service + " arrêtée");
        }
    }

    /**
     * Effectue un HealthCheck gRPC et construit la mesure correspondante. Ne lève jamais
     * d'exception : tout échec produit une mesure DOWN.
     *
     * @return la mesure de santé
     */
    public MesureSante pinger() {
        long t0 = System.nanoTime();
        MesureSante m;
        try {
            HealthResp r = ClientCalls.blockingUnaryCall(canal, methodeHealth,
                    CallOptions.DEFAULT.withDeadlineAfter(DELAI_GRPC_MS, TimeUnit.MILLISECONDS),
                    HealthReq.newBuilder().setServiceName(service).build());
            long latence = (System.nanoTime() - t0) / 1_000_000;
            m = new MesureSante(service, System.currentTimeMillis(), convertirStatut(r),
                    latence, r.getCpuLoad(), r.getUptimeMs());
        } catch (StatusRuntimeException e) {
            m = MesureSante.down(service, (System.nanoTime() - t0) / 1_000_000);
            if (!MesureSante.DOWN.equals(dernierStatut)) {
                LOG.warning(() -> "Sonde " + service + " : HealthCheck en échec (" + e.getStatus().getCode() + ") → DOWN");
            }
            // Évite que le canal gRPC attende son délai de reconnexion exponentiel :
            // la prochaine tentative se reconnecte immédiatement si le service est revenu.
            canal.resetConnectBackoff();
        } catch (RuntimeException e) {
            m = MesureSante.down(service, (System.nanoTime() - t0) / 1_000_000);
            LOG.warning(() -> "Sonde " + service + " : erreur inattendue (" + e + ") → DOWN");
        }
        if (dernierStatut != null && !dernierStatut.equals(m.statut)) {
            String avant = dernierStatut;
            String apres = m.statut;
            LOG.info(() -> "Sonde " + service + " : " + avant + " → " + apres);
        }
        dernierStatut = m.statut;
        return m;
    }

    /**
     * Envoie la mesure au Monitor : connexion TCP, une ligne JSON, fermeture.
     *
     * @param m mesure à transmettre
     * @return {@code true} si l'envoi a réussi
     */
    public boolean envoyerIPC(MesureSante m) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hoteIpc, ipcPort), DELAI_CONNEXION_IPC_MS);
            s.setSoTimeout(DELAI_CONNEXION_IPC_MS);
            Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            w.write(m.versJson());
            w.write('\n');
            w.flush();
            if (!monitorJoignable) {
                LOG.info(() -> "Sonde " + service + " : Monitor de nouveau joignable");
            }
            monitorJoignable = true;
            return true;
        } catch (IOException e) {
            if (monitorJoignable) {   // un seul avertissement tant que le Monitor reste injoignable
                LOG.warning(() -> "Sonde " + service + " : Monitor injoignable sur " + hoteIpc + ":" + ipcPort
                        + " (" + e.getMessage() + ") — mesures perdues jusqu'à son retour");
            }
            monitorJoignable = false;
            return false;
        }
    }

    /** Demande l'arrêt de la sonde (effectif immédiatement, même pendant l'attente). */
    public void arreter() {
        active = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** @return nom du service (clé dans la KnowledgeBase) */
    public String service() {
        return service;
    }

    /** @return port gRPC surveillé */
    public int grpcPort() {
        return grpcPort;
    }

    /** @return type de service surveillé */
    public ServiceMesh type() {
        return type;
    }

    private static String convertirStatut(HealthResp r) {
        return switch (r.getStatus()) {
            case SERVING -> MesureSante.SERVING;
            case DOWN -> MesureSante.DOWN;
            // DEGRADED, ou valeur inconnue : le service répond mais pas de façon nominale
            default -> MesureSante.DEGRADED;
        };
    }

    /**
     * Ferme proprement un canal gRPC (attente courte, puis arrêt forcé).
     *
     * @param canal canal à fermer
     */
    public static void fermerCanal(ManagedChannel canal) {
        canal.shutdown();
        try {
            if (!canal.awaitTermination(1, TimeUnit.SECONDS)) {
                canal.shutdownNow();
            }
        } catch (InterruptedException e) {
            canal.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
