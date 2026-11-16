package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Démarrage commun d'un processus de service gRPC : création de l'{@link EtatSante},
 * enregistrement du service métier et du service d'administration, crochet d'arrêt propre.
 *
 * <p>Chaque service tourne dans <b>son propre processus Java</b> (voir {@link CalcServer},
 * {@link StorageServer}, {@link NotifServer}).</p>
 */
public final class ServeurMesh {

    private static final Logger LOG = Journal.logger("Serveur");

    private ServeurMesh() {
    }

    /**
     * Démarre un serveur gRPC (non bloquant). Utilisé par les {@code main} et par les tests.
     *
     * @param etat    état de santé de l'instance (fournit le nom et le port)
     * @param service implémentation métier à exposer
     * @return le serveur démarré
     * @throws IOException si le port est déjà utilisé
     */
    public static Server demarrer(EtatSante etat, BindableService service) throws IOException {
        return ServerBuilder.forPort(etat.port())
                .addService(service)
                .addService(new AdminServiceImpl(etat))
                .build()
                .start();
    }

    /**
     * Point d'entrée commun des trois {@code main} : lit le port (premier argument,
     * facultatif), démarre le serveur et bloque jusqu'à l'arrêt du processus.
     *
     * @param type    service à lancer
     * @param args    arguments de la ligne de commande ({@code [port]})
     * @param fabrique construit l'implémentation métier à partir de l'état de santé
     */
    public static void lancer(ServiceMesh type, String[] args, Function<EtatSante, BindableService> fabrique) {
        Journal.configurer();
        int port = type.portParDefaut();
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException e) {
                LOG.severe("Port invalide : " + args[0] + " — usage : " + type.classePrincipale() + " [port]");
                System.exit(2);
            }
        }
        EtatSante etat = new EtatSante(type.nom(), port);
        Server serveur;
        try {
            serveur = demarrer(etat, fabrique.apply(etat));
        } catch (IOException e) {
            LOG.severe(type.nom() + " : impossible d'écouter sur le port " + port + " (" + e.getMessage() + ")");
            System.exit(1);
            return;
        }
        LOG.info(type.nom() + " démarré sur le port " + port + " (PID " + ProcessHandle.current().pid() + ")");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            etat.marquerEnArret();
            // System.out plutôt que le logger : java.util.logging est déjà désactivé
            // pendant l'exécution des crochets d'arrêt de la JVM.
            System.out.println(java.time.LocalDateTime.now() + " " + type.nom()
                    + " : arrêt en cours (PID " + ProcessHandle.current().pid() + ")");
            serveur.shutdown();
            try {
                if (!serveur.awaitTermination(3, TimeUnit.SECONDS)) {
                    serveur.shutdownNow();
                }
            } catch (InterruptedException e) {
                serveur.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "arret-" + type.nom()));

        try {
            serveur.awaitTermination();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            serveur.shutdownNow();
        }
    }
}
