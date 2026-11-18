package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import ca.usherbrooke.ift605.mesh.sonde.Sonde;
import ca.usherbrooke.ift605.mesh.sonde.LanceurSondes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordonne les composants de la boucle MAPE-K.
 */
public class GestionnaireAutonome {

    private final KnowledgeBase kb = new KnowledgeBase();

    private final Monitor monitor = new Monitor(kb);

    private final Analyzer analyzer = new Analyzer(kb);

    private final Planner planner = new Planner();

    private final Executor executor = new Executor(kb);

    private final ClientAdaptatif client = new ClientAdaptatif(kb);

    private final List<Sonde> sondes = new ArrayList<>();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    private final AtomicLong cycles = new AtomicLong();

    private final boolean automatique;

    public GestionnaireAutonome() {
        this(true);
    }

    public GestionnaireAutonome(boolean automatique) {
        this.automatique = automatique;
    }

    /**
     * Demarre le Monitor, les services et les sondes.
     */
    public void demarrer() throws IOException {

        monitor.demarrerEnArrierePlan(
                ServiceMesh.PORT_IPC_DEFAUT
        );

        executor.demarrerServices();

        sondes.addAll(
                LanceurSondes.demarrerToutes(
                        ServiceMesh.HOTE_DEFAUT,
                        ServiceMesh.PORT_IPC_DEFAUT
                )
        );

        if (automatique) {

            scheduler.scheduleAtFixedRate(
                    this::executerCycle,
                    10,
                    10,
                    TimeUnit.SECONDS
            );
        }

        System.out.println(
                "[Gestionnaire] Demarrage termine"
        );
    }

    /**
     * Execute un cycle complet MAPE-K.
     */
    private void executerCycle() {

        try {

            long numero = cycles.incrementAndGet();

            System.out.println(
                    "\n=== Cycle MAPE-K " + numero + " ==="
            );

            var anomalies = analyzer.analyser();

            for (Anomalie anomalie : anomalies) {

                System.out.println(
                        "[Analyze] "
                        + anomalie.getService()
                        + " : " + anomalie.getType()
                );
            }

            var actions = planner.planifier(anomalies);

            for (Action action : actions) {

                System.out.println(
                        "[Plan] "
                        + action.getService()
                        + " : " + action.getType()
                );

                executor.executer(action);
            }

            if (anomalies.isEmpty()) {

                System.out.println(
                        "[Analyze] Aucune anomalie"
                );
            }

            try {
                client.calculer(6, 7, "*");
            } catch (RuntimeException e) {
                System.out.println(
                        "[Client] Echec : " + e.getMessage()
                );
            }

        } catch (RuntimeException e) {

            System.err.println(
                    "[Gestionnaire] Erreur : " + e.getMessage()
            );
        }
    }

    public KnowledgeBase getKnowledgeBase() {
        return kb;
    }

    public Executor getExecutor() {
        return executor;
    }

    public long getCycles() {
        return cycles.get();
    }

    /**
     * Arrete les composants du gestionnaire.
     */
    public void arreter() {

        scheduler.shutdownNow();

        try {
            scheduler.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (Sonde sonde : sondes) {
            sonde.arreter();
        }

        executor.arreterServices();

        monitor.arreter();

        System.out.println(
                "[Gestionnaire] Arret termine"
        );
    }

    public static void main(String[] args) throws Exception {

        GestionnaireAutonome gestionnaire =
                new GestionnaireAutonome();

        Runtime.getRuntime().addShutdownHook(
                new Thread(gestionnaire::arreter)
        );

        gestionnaire.demarrer();

        Thread.currentThread().join();
    }
}