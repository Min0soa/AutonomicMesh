package ca.usherbrooke.ift605.chaos;

import ca.usherbrooke.ift605.mapek.GestionnaireAutonome;
import ca.usherbrooke.ift605.mesh.commun.MesureSante;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Collecte les mesures de detection et de recuperation.
 */
public class Metriques {

    private final GestionnaireAutonome gestionnaire;

    private final boolean automatique;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    private String service;

    private long instantPanne;
    private long instantDetection;
    private long cycleDetection;

    private int nbPannes;
    private int nbDetectees15s;
    private int nbRecuperees;
    private int nbSansDetection;

    private long sommeMTTR;
    private long sommeCycles;

    private double sommeCPU;
    private double sommeMemoire;
    private int nbEchantillons;
    private int nbEchantillonsCPU;

    private boolean interventionManuelle;

    public Metriques(
            GestionnaireAutonome gestionnaire,
            boolean automatique) {

        this.gestionnaire = gestionnaire;
        this.automatique = automatique;
    }

    public void demarrer() {

        scheduler.scheduleAtFixedRate(
                this::observer,
                0,
                1,
                TimeUnit.SECONDS
        );
    }

    public synchronized boolean panneEnCours() {
        return service != null;
    }

    public boolean estDisponible(String nomService) {

        var mesure = gestionnaire.getKnowledgeBase()
                .getMesure(nomService)
                .orElse(null);

        return mesure != null
                && MesureSante.SERVING.equals(mesure.statut)
                && System.currentTimeMillis()
                        - mesure.timestamp <= 15000;
    }

    public synchronized void enregistrerPanne(
            String cible, long instant) {

        service = cible;
        instantPanne = instant;
        instantDetection = 0;
        cycleDetection = 0;
        interventionManuelle = false;

        nbPannes++;
    }

    private synchronized void observer() {

        try {

            mesurerRessources();

            if (service == null) {
                return;
            }

            long maintenant = System.currentTimeMillis();

            var historique = gestionnaire.getKnowledgeBase()
                    .getHistorique(service);

            // Rechercher la premiere mesure DOWN apres la panne
            if (instantDetection == 0) {

                for (MesureSante m : historique) {

                    if (m.timestamp >= instantPanne
                            && MesureSante.DOWN.equals(m.statut)) {

                        instantDetection = m.timestamp;

                        cycleDetection = gestionnaire.getCycles();

                        if (instantDetection - instantPanne
                                <= 15000) {

                            nbDetectees15s++;
                        }

                        System.out.println(
                                "[Metriques] Detection : "
                                + service + " apres "
                                + (instantDetection - instantPanne)
                                + " ms"
                        );

                        break;
                    }
                }
            }

            // Panne non detectee apres 60 secondes
            if (instantDetection == 0) {

                if (maintenant - instantPanne > 60000) {

                    nbSansDetection++;

                    gestionnaire.getExecutor()
                            .redemarrer(service);

                    service = null;
                }

                return;
            }

            // Simulation d'une intervention humaine apres 20 s
            if (!automatique
                    && !interventionManuelle
                    && maintenant - instantDetection >= 20000) {

                gestionnaire.getExecutor()
                        .redemarrer(service);

                interventionManuelle = true;

                System.out.println(
                        "[Manuel] Redemarrage de " + service
                );
            }

            // Rechercher le retour a l'etat SERVING
            for (MesureSante m : historique) {

                if (m.timestamp > instantDetection
                        && MesureSante.SERVING.equals(m.statut)
                        && m.latenceMs <= 500
                        && m.cpuLoad <= 0.90) {

                    long mttr = m.timestamp - instantDetection;

                    long cycles = gestionnaire.getCycles()
                            - cycleDetection;

                    sommeMTTR += mttr;
                    sommeCycles += cycles;
                    nbRecuperees++;

                    System.out.println(
                            "[Metriques] Recuperation : "
                            + service
                            + " - MTTR : " + mttr + " ms"
                            + " - Cycles observes : " + cycles
                    );

                    service = null;

                    afficherBilan();

                    break;
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        } catch (Exception e) {

            System.err.println(
                    "[Metriques] Erreur : " + e.getMessage()
            );
        }
    }

    private void mesurerRessources() {

        var os = ManagementFactory.getOperatingSystemMXBean();

        if (os instanceof
                com.sun.management.OperatingSystemMXBean bean) {

            double cpu = bean.getProcessCpuLoad();

            if (cpu >= 0) {
                sommeCPU += cpu * 100;
                nbEchantillonsCPU++;
            }
        }

        Runtime runtime = Runtime.getRuntime();

        double memoire = (
                runtime.totalMemory() - runtime.freeMemory()
        ) / (1024.0 * 1024.0);

        sommeMemoire += memoire;

        nbEchantillons++;
    }

    public synchronized void afficherBilan() {

        double mttr = nbRecuperees == 0 ? 0
                : sommeMTTR / (1000.0 * nbRecuperees);

        double detection = nbPannes == 0 ? 0
                : 100.0 * nbDetectees15s / nbPannes;

        double cycles = nbRecuperees == 0 ? 0
                : (double) sommeCycles / nbRecuperees;

        double cpu = nbEchantillonsCPU == 0 ? 0
                : sommeCPU / nbEchantillonsCPU;

        double memoire = nbEchantillons == 0 ? 0
                : sommeMemoire / nbEchantillons;

        System.out.println("\n===== BILAN =====");

        System.out.println(
                "Mode : "
                + (automatique ? "MAPE-K" : "MANUEL SIMULE")
        );

        System.out.println("Pannes injectees : " + nbPannes);

        System.out.println("Pannes recuperees : " + nbRecuperees);

        System.out.println(
                "Pannes sans detection : " + nbSansDetection
        );

        System.out.printf(
                Locale.ROOT, "MTTR : %.2f s%n", mttr
        );

        System.out.printf(
                Locale.ROOT,
                "Detection dans les 15 s : %.2f %% %n",
                detection
        );

        System.out.printf(
                Locale.ROOT,
                "Cycles moyens observes : %.2f%n",
                cycles
        );

        System.out.printf(
                Locale.ROOT, "CPU moyen : %.2f %% %n", cpu
        );

        System.out.printf(
                Locale.ROOT,
                "Memoire heap moyenne : %.2f Mio%n",
                memoire
        );

        System.out.println(
                "Faux positifs : analyse des journaux requise"
        );
    }

    public void arreter() {
        scheduler.shutdownNow();
    }
}