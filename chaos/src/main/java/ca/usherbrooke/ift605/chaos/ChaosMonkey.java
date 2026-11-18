package ca.usherbrooke.ift605.chaos;

import ca.usherbrooke.ift605.mapek.GestionnaireAutonome;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Injecte des pannes aleatoires dans les microservices.
 */
public class ChaosMonkey {

    private final GestionnaireAutonome gestionnaire;

    private final Metriques metriques;

    private final Random random = new Random(42);

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public ChaosMonkey(
            GestionnaireAutonome gestionnaire,
            Metriques metriques) {

        this.gestionnaire = gestionnaire;
        this.metriques = metriques;
    }

    public void demarrer() {

        scheduler.scheduleAtFixedRate(
                this::injecterPanne,
                30,
                30,
                TimeUnit.SECONDS
        );
    }

    private void injecterPanne() {

        try {

            if (metriques.panneEnCours()) {
                return;
            }

            var services = gestionnaire.getExecutor()
                    .servicesActifs()
                    .stream()
                    .filter(metriques::estDisponible)
                    .sorted()
                    .toList();

            if (services.isEmpty()) {
                return;
            }

            String cible = services.get(
                    random.nextInt(services.size())
            );

            long instant = System.currentTimeMillis();

            if (gestionnaire.getExecutor()
                    .provoquerPanne(cible)) {

                metriques.enregistrerPanne(cible, instant);

                System.out.println(
                        "[Chaos] Panne injectee : "
                        + cible + " - " + instant
                );
            }

        } catch (RuntimeException e) {

            System.err.println(
                    "[Chaos] Erreur : " + e.getMessage()
            );
        }
    }

    public void arreter() {
        scheduler.shutdownNow();
    }
}