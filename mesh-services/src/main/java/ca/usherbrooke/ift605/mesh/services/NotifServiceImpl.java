package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifResponse;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Service de notification interne : reçoit un événement, le journalise, le conserve dans un
 * petit tampon (les {@value #TAILLE_TAMPON} derniers) et confirme sa prise en charge.
 *
 * <p>Un événement dont le nom est vide est refusé ({@code accepted=false}) sans erreur gRPC.</p>
 */
public class NotifServiceImpl extends NotifServiceGrpc.NotifServiceImplBase {

    /** Nombre maximal d'événements conservés en mémoire. */
    public static final int TAILLE_TAMPON = 100;

    private static final Logger LOG = Journal.logger("NotifService");

    private final EtatSante etat;
    private final ConcurrentLinkedDeque<NotifRequest> derniers = new ConcurrentLinkedDeque<>();
    private final AtomicLong nbRecus = new AtomicLong(0);

    /** @param etat état de santé partagé du processus */
    public NotifServiceImpl(EtatSante etat) {
        this.etat = etat;
    }

    @Override
    public void notify(NotifRequest req, StreamObserver<NotifResponse> resp) {
        try {
            etat.appliquerLatenceSimulee();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.onError(Status.CANCELLED.withDescription("Notification interrompue").asRuntimeException());
            return;
        }
        boolean accepte = !req.getEvent().isBlank();
        long total = nbRecus.get();
        if (accepte) {
            total = nbRecus.incrementAndGet();
            derniers.addLast(req);
            while (derniers.size() > TAILLE_TAMPON) {
                derniers.pollFirst();
            }
            LOG.info(() -> "Événement reçu : " + req.getEvent() + " — " + req.getPayload());
        }
        resp.onNext(NotifResponse.newBuilder().setAccepted(accepte).setReceivedCount(total).build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        etat.repondreHealthCheck(resp);
    }

    /** @return copie des derniers événements reçus (du plus ancien au plus récent) */
    public List<NotifRequest> derniersEvenements() {
        return List.copyOf(derniers);
    }
}
