package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import ca.usherbrooke.ift605.mesh.sonde.LanceurSondes;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Programme de validation de la Partie 1 (sans la boucle Analyze/Plan/Execute) : démarre le
 * Monitor et, optionnellement, les trois sondes dans le même processus, puis affiche l'état de
 * la KnowledgeBase toutes les 5 secondes.
 *
 * <pre>
 * java -cp mape-k/target/mape-k-all.jar ca.usherbrooke.ift605.mapek.MonitorMain [port] [--avec-sondes]
 * </pre>
 */
public final class MonitorMain {

    private MonitorMain() {
    }

    /**
     * @param args {@code [port] [--avec-sondes]}
     * @throws InterruptedException si le thread principal est interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        Journal.configurer();
        int port = ServiceMesh.PORT_IPC_DEFAUT;
        boolean avecSondes = false;
        for (String a : args) {
            if (a.equals("--avec-sondes")) {
                avecSondes = true;
            } else {
                port = Integer.parseInt(a);
            }
        }
        KnowledgeBase kb = new KnowledgeBase();
        Monitor monitor = new Monitor(kb);
        monitor.demarrerEnArrierePlan(port);
        if (avecSondes) {
            LanceurSondes.demarrerToutes(ServiceMesh.HOTE_DEFAUT, port);
        }

        ScheduledExecutorService affichage = Executors.newSingleThreadScheduledExecutor();
        affichage.scheduleAtFixedRate(() -> afficher(kb, monitor), 6, 5, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            affichage.shutdownNow();
            monitor.arreter();
        }, "arret-monitor"));
        Thread.currentThread().join();
    }

    /** Affiche l'état courant de la KB sous forme de tableau. */
    static void afficher(KnowledgeBase kb, Monitor monitor) {
        Map<String, MesureSante> mesures = new TreeMap<>(kb.getMesures());
        long maintenant = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%n┌─ KnowledgeBase — %d mesures reçues, %d rejetées%n",
                monitor.getNbRecues(), monitor.getNbRejetees()));
        sb.append(String.format(Locale.ROOT, "│ %-16s %-9s %9s %6s %9s %8s %7s%n",
                "Service", "Statut", "Latence", "CPU", "Uptime", "Âge", "Hist."));
        for (var e : mesures.entrySet()) {
            MesureSante m = e.getValue();
            sb.append(String.format(Locale.ROOT, "│ %-16s %-9s %7d ms %6.2f %8ds %6.1fs %7d%n",
                    e.getKey(), m.statut, m.latenceMs, m.cpuLoad, m.uptimeMs / 1000,
                    (maintenant - m.timestamp) / 1000.0, kb.getHistorique(e.getKey()).size()));
        }
        if (mesures.isEmpty()) {
            sb.append("│ (aucune mesure reçue pour l'instant)\n");
        }
        sb.append("└─");
        System.out.println(sb);
    }
}
