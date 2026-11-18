package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import ca.usherbrooke.ift605.mesh.sonde.Sonde;
import ca.usherbrooke.ift605.mesh.sonde.LanceurSondes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Execute les actions correctives decidees par le Planner.
 */
public class Executor {

    private final KnowledgeBase kb;

    // Processus des microservices
    private final ConcurrentHashMap<String, Process> processus =
            new ConcurrentHashMap<>();

    // Dates des derniers demarrages et redemarrages
    private final ConcurrentHashMap<String, Long> derniersRedemarrages =
            new ConcurrentHashMap<>();

    // Sondes des instances supplementaires
    private final ConcurrentHashMap<String, Sonde> sondesSupplementaires =
            new ConcurrentHashMap<>();

    public Executor(KnowledgeBase kb) {
        this.kb = kb;
    }

    /**
     * Demarre les trois microservices principaux.
     */
    public synchronized void demarrerServices() throws IOException {

        for (ServiceMesh service : ServiceMesh.values()) {

            Process actuel = processus.get(service.nom());

            if (actuel != null && actuel.isAlive()) {
                continue;
            }

            Process p = service.demarrerProcessus(
                    service.portParDefaut()
            );

            processus.put(service.nom(), p);

            derniersRedemarrages.put(
                    service.nom(),
                    System.currentTimeMillis()
            );

            System.out.println(
                    "[Executor] Demarrage de " + service.nom()
                    + " - PID : " + p.pid()
            );
        }
    }

    /**
     * Redemarre un microservice sur son propre port.
     */
    public synchronized void redemarrer(String nomService)
            throws IOException, InterruptedException {

        ServiceMesh service = ServiceMesh.parNom(nomService);

        String nom = service.nom();
        int port = service.portParDefaut();

        // Cas d'une instance supplementaire
        if (nomService.contains("#")) {

            nom = nomService;
            port = service.portParDefaut() + 10;
        }

        Process ancien = processus.get(nom);

        if (ancien != null && ancien.isAlive()) {

            ancien.destroy();

            if (!ancien.waitFor(5, TimeUnit.SECONDS)) {

                ancien.destroyForcibly();

                if (!ancien.waitFor(5, TimeUnit.SECONDS)) {

                    throw new IOException(
                            "Impossible d'arreter " + nom
                    );
                }
            }
        }

        Process nouveau = service.demarrerProcessus(port);

        processus.put(nom, nouveau);

        derniersRedemarrages.put(
                nom,
                System.currentTimeMillis()
        );

        System.out.println(
                "[Executor] Relance de " + nom
                + " - Port : " + port
                + " - Nouveau PID : " + nouveau.pid()
        );
    }

    /**
     * Traite une panne en evitant les redemarrages inutiles.
     */
    private synchronized void traiterPanne(String service)
            throws IOException, InterruptedException {

        MesureSante mesure = kb.getMesure(service).orElse(null);

        if (mesure == null
                || !MesureSante.DOWN.equals(mesure.statut)) {

            return;
        }

        Long dernier = derniersRedemarrages.get(service);

        if (dernier != null) {

            if (mesure.timestamp <= dernier
                    || System.currentTimeMillis() - dernier < 15000) {

                return;
            }
        }

        redemarrer(service);
    }

    /**
     * Cree une instance supplementaire du microservice.
     */
    public synchronized void mettreAEchelle(String nomService)
            throws IOException {

        ServiceMesh service = ServiceMesh.parNom(nomService);

        int port = service.portParDefaut() + 10;

        String nomInstance = service.nom() + "#" + port;

        // Verifier si l'instance existe deja
        Process actuel = processus.get(nomInstance);

        if (actuel != null && actuel.isAlive()) {
            return;
        }

        // Demarrer une nouvelle instance
        Process nouveau = service.demarrerProcessus(port);

        processus.put(nomInstance, nouveau);

        derniersRedemarrages.put(
                nomInstance,
                System.currentTimeMillis()
        );

        // Demarrer une sonde si elle n'existe pas deja
        if (!sondesSupplementaires.containsKey(nomInstance)) {

            Sonde sonde = new Sonde(
                    nomInstance,
                    port,
                    ServiceMesh.PORT_IPC_DEFAUT
            );

            LanceurSondes.demarrer(sonde);

            sondesSupplementaires.put(nomInstance, sonde);
        }

        System.out.println(
                "[Executor] Nouvelle instance : " + nomInstance
                + " - Port : " + port
                + " - PID : " + nouveau.pid()
        );
    }

    /**
     * Verifie si une instance est operationnelle.
     */
    private boolean estSaine(String nomInstance) {

        Process p = processus.get(nomInstance);

        if (p == null || !p.isAlive()) {
            return false;
        }

        MesureSante mesure = kb.getMesure(nomInstance).orElse(null);

        if (mesure == null
                || !MesureSante.SERVING.equals(mesure.statut)
                || mesure.latenceMs > 500
                || mesure.cpuLoad > 0.90) {

            return false;
        }

        // Verifier que la mesure est recente
        if (System.currentTimeMillis() - mesure.timestamp > 15000) {

            return false;
        }

        // Verifier que la mesure correspond au processus actuel
        Long dernier = derniersRedemarrages.get(nomInstance);

        if (dernier != null && mesure.timestamp <= dernier) {

            return false;
        }

        return true;
    }

    /**
     * Redirige les requetes vers une instance saine.
     */
    public synchronized void reacheminer(String nomService)
            throws IOException {

        ServiceMesh service = ServiceMesh.parNom(nomService);

        String principal = service.nom();

        int port = service.portParDefaut() + 10;

        String secondaire = principal + "#" + port;

        // Si l'instance secondaire est degradee
        if (nomService.equals(secondaire)) {

            if (estSaine(principal)
                    && secondaire.equals(kb.getRoute(principal))) {

                kb.setRoute(principal, principal);

                System.out.println(
                        "[Executor] Retour vers " + principal
                );
            }

            return;
        }

        // Si l'instance secondaire n'existe pas
        Process actuel = processus.get(secondaire);

        if (actuel == null || !actuel.isAlive()) {

            mettreAEchelle(principal);

            return;
        }

        // Attendre que l'instance secondaire soit saine
        if (!estSaine(secondaire)) {

            return;
        }

        // Eviter de modifier plusieurs fois la meme route
        if (secondaire.equals(kb.getRoute(principal))) {

            return;
        }

        kb.setRoute(principal, secondaire);

        System.out.println(
                "[Executor] Reacheminement : "
                + principal + " -> " + secondaire
        );
    }

    /**
     * Execute une action corrective proposee par le Planner.
     */
    public void executer(Action action) {

        String service = action.getService();

        try {

            switch (action.getType()) {

                case REDEMARRER:
                    traiterPanne(service);
                    break;

                case REACHEMINEMENT:
                    reacheminer(service);
                    break;

                case MISE_A_ECHELLE:
                    mettreAEchelle(service);
                    break;
            }

        } catch (IOException e) {

            System.err.println(
                    "[Executor] Erreur pour " + service
                    + " : " + e.getMessage()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }

    /**
     * Provoque une panne sur un processus gere par Executor.
     */
    public synchronized boolean provoquerPanne(String nomService) {

        Process p = processus.get(nomService);

        if (p == null || !p.isAlive()) {
            return false;
        }

        p.destroyForcibly();

        return true;
    }

    /**
     * Retourne les noms des services principaux actifs.
     */
    public synchronized List<String> servicesActifs() {

        return processus.entrySet().stream()
                .filter(e -> !e.getKey().contains("#"))
                .filter(e -> e.getValue().isAlive())
                .map(e -> e.getKey())
                .toList();
    }

    /**
     * Arrete les sondes supplementaires et les processus.
     */
    public synchronized void arreterServices() {

        for (Sonde sonde : sondesSupplementaires.values()) {

            sonde.arreter();
        }

        for (Process p : processus.values()) {

            if (p.isAlive()) {

                p.destroyForcibly();
            }
        }

        sondesSupplementaires.clear();

        processus.clear();
    }
}