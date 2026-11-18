package ca.usherbrooke.ift605.chaos;

import ca.usherbrooke.ift605.mapek.GestionnaireAutonome;

/**
 * Lance une experience de chaos avec ou sans MAPE-K.
 */
public class ExperienceChaos {

    public static void main(String[] args) throws Exception {

        boolean automatique = args.length == 0
                || !args[0].equalsIgnoreCase("manuel");

        int duree = args.length > 1
                ? Integer.parseInt(args[1])
                : 300;

        GestionnaireAutonome gestionnaire =
                new GestionnaireAutonome(automatique);

        Metriques metriques =
                new Metriques(gestionnaire, automatique);

        ChaosMonkey chaos =
                new ChaosMonkey(gestionnaire, metriques);

        try {

            gestionnaire.demarrer();

            // Attendre la stabilisation des services
            Thread.sleep(15000);

            metriques.demarrer();

            chaos.demarrer();

            Thread.sleep(duree * 1000L);

            chaos.arreter();

            // Laisser la derniere panne se terminer
            Thread.sleep(45000);

        } finally {

            chaos.arreter();

            metriques.arreter();

            metriques.afficherBilan();

            gestionnaire.arreter();
        }
    }
}