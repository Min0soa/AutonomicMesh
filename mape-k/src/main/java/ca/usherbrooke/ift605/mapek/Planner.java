package ca.usherbrooke.ift605.mapek;

import java.util.ArrayList;
import java.util.List;

/**
 * Determine les actions correctives a partir
 * des anomalies detectees par Analyzer.
 */
public class Planner {

    /**
     * Transforme les anomalies en actions correctives.
     */
    public List<Action> planifier(List<Anomalie> anomalies) {

        List<Action> actions = new ArrayList<>();

        for (Anomalie anomalie : anomalies) {

            String service = anomalie.getService();

            switch (anomalie.getType()) {

                case PANNE_TOTALE:
                    actions.add(new Action(
                            service, TypeAction.REDEMARRER));
                    break;

                case DEGRADATION:
                    actions.add(new Action(
                            service, TypeAction.REACHEMINEMENT));
                    break;

                case SURCHARGE:
                    actions.add(new Action(
                            service, TypeAction.MISE_A_ECHELLE));
                    break;
            }
        }

        return actions;
    }
}