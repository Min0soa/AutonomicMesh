package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Analyse les mesures de sante pour detecter les anomalies.
 */
public class Analyzer {

    private final KnowledgeBase kb;

    // Services actuellement declares en panne
    private final Set<String> servicesEnPanne = new HashSet<>();

    public Analyzer(KnowledgeBase kb) {
        this.kb = kb;
    }

    /**
     * Analyse les mesures et retourne les anomalies detectees.
     */
    public synchronized List<Anomalie> analyser() {

        List<Anomalie> anomalies = new ArrayList<>();

        for (var entry : kb.getMesures().entrySet()) {

            String service = entry.getKey();
            MesureSante m = entry.getValue();

            if (MesureSante.DOWN.equals(m.statut)) {

                // Compter uniquement une nouvelle panne
                if (servicesEnPanne.add(service)) {

                    kb.incrementerPannes(service);
                }

                anomalies.add(
                        new Anomalie(
                                service,
                                TypeAnomalie.PANNE_TOTALE
                        )
                );

            } else {

                // Le service n'est plus en panne totale
                servicesEnPanne.remove(service);

                if (m.latenceMs > 500) {

                    anomalies.add(
                            new Anomalie(
                                    service,
                                    TypeAnomalie.DEGRADATION
                            )
                    );

                } else if (m.cpuLoad > 0.90) {

                    anomalies.add(
                            new Anomalie(
                                    service,
                                    TypeAnomalie.SURCHARGE
                            )
                    );
                }
            }
        }

        return anomalies;
    }
}