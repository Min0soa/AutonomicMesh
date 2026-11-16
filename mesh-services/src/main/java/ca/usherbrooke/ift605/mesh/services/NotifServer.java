package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;

/**
 * Processus indépendant du service NotifService.
 *
 * <p>Lancement : {@code java -cp mesh-services/target/mesh-services-all.jar
 * ca.usherbrooke.ift605.mesh.services.NotifServer [port]} (port par défaut : 50053).</p>
 */
public final class NotifServer {

    private NotifServer() {
    }

    /**
     * @param args {@code [port]}
     */
    public static void main(String[] args) {
        ServeurMesh.lancer(ServiceMesh.NOTIF, args, NotifServiceImpl::new);
    }
}
