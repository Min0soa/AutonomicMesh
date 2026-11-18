package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcResponse;
import ca.usherbrooke.ift605.mesh.sonde.Sonde;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Client gRPC utilisant les routes de la KnowledgeBase.
 */
public class ClientAdaptatif {

    private final KnowledgeBase kb;

    public ClientAdaptatif(KnowledgeBase kb) {
        this.kb = kb;
    }

    /**
     * Determine le port a utiliser pour un service.
     */
    public int obtenirPort(String nomService) {

        ServiceMesh service = ServiceMesh.parNom(nomService);

        String route = kb.getRoute(service.nom());

        if (route.contains("#")) {

            return Integer.parseInt(route.split("#")[1]);
        }

        return service.portParDefaut();
    }

    /**
     * Execute un calcul sur l'instance selectionnee.
     */
    public CalcResponse calculer(double a, double b, String operation) {

        int port = obtenirPort("CalcService");

        ManagedChannel canal = ManagedChannelBuilder
                .forAddress(ServiceMesh.HOTE_DEFAUT, port)
                .usePlaintext()
                .build();

        try {

            var stub = CalcServiceGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(3, TimeUnit.SECONDS);

            CalcResponse reponse = stub.compute(
                    CalcRequest.newBuilder()
                            .setA(a)
                            .setB(b)
                            .setOp(operation)
                            .build()
            );

            System.out.println(
                    "[ClientAdaptatif] Port : " + port
                    + " - Resultat : " + reponse.getResult()
                    + " - Latence : " + reponse.getLatencyMs()
                    + " ms"
            );

            return reponse;

        } finally {

            Sonde.fermerCanal(canal);
        }
    }
}