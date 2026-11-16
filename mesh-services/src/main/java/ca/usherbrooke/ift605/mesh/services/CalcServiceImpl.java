package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Service de calcul : exécute une opération arithmétique ({@code + - * /}) et retourne le
 * résultat avec la latence de traitement.
 *
 * <p>La division par zéro retourne {@code NaN} (comportement du squelette fourni) ; un
 * opérateur inconnu est refusé avec {@code INVALID_ARGUMENT}.</p>
 */
public class CalcServiceImpl extends CalcServiceGrpc.CalcServiceImplBase {

    private final EtatSante etat;

    /** @param etat état de santé partagé du processus */
    public CalcServiceImpl(EtatSante etat) {
        this.etat = etat;
    }

    @Override
    public void compute(CalcRequest req, StreamObserver<CalcResponse> resp) {
        long t0 = System.nanoTime();
        try {
            etat.appliquerLatenceSimulee();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.onError(Status.CANCELLED.withDescription("Calcul interrompu").asRuntimeException());
            return;
        }
        double a = req.getA();
        double b = req.getB();
        double result;
        switch (req.getOp()) {
            case "+" -> result = a + b;
            case "-" -> result = a - b;
            case "*" -> result = a * b;
            case "/" -> result = b != 0 ? a / b : Double.NaN;
            default -> {
                resp.onError(Status.INVALID_ARGUMENT
                        .withDescription("Opérateur inconnu : '" + req.getOp() + "' (attendu + - * /)")
                        .asRuntimeException());
                return;
            }
        }
        resp.onNext(CalcResponse.newBuilder()
                .setResult(result)
                .setLatencyMs((System.nanoTime() - t0) / 1_000_000)
                .build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        etat.repondreHealthCheck(resp);
    }
}
