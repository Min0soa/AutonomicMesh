package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;

/**
 * Processus indépendant du service CalcService.
 *
 * <p>Lancement : {@code java -cp mesh-services/target/mesh-services-all.jar
 * ca.usherbrooke.ift605.mesh.services.CalcServer [port]} (port par défaut : 50051).</p>
 */
public final class CalcServer {

    private CalcServer() {
    }

    /**
     * @param args {@code [port]}
     */
    public static void main(String[] args) {
        ServeurMesh.lancer(ServiceMesh.CALC, args, CalcServiceImpl::new);
    }
}
