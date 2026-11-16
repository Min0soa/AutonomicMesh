package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;

/**
 * Processus indépendant du service StorageService.
 *
 * <p>Lancement : {@code java -cp mesh-services/target/mesh-services-all.jar
 * ca.usherbrooke.ift605.mesh.services.StorageServer [port]} (port par défaut : 50052).</p>
 */
public final class StorageServer {

    private StorageServer() {
    }

    /**
     * @param args {@code [port]}
     */
    public static void main(String[] args) {
        ServeurMesh.lancer(ServiceMesh.STORAGE, args, StorageServiceImpl::new);
    }
}
