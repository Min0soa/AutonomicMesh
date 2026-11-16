package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutResponse;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service de stockage clé-valeur en mémoire.
 *
 * <ul>
 *   <li>{@code Put(key, value)} : enregistre (ou remplace) la valeur et retourne le numéro de
 *       version attribué ;</li>
 *   <li>{@code Get(key)} : retourne la valeur et sa version, ou {@code found=false}.</li>
 * </ul>
 * <p>Le stockage est volatile : il est perdu si le processus est redémarré (limite à
 * mentionner dans le rapport). Thread-safe grâce à {@link ConcurrentHashMap}.</p>
 */
public class StorageServiceImpl extends StorageServiceGrpc.StorageServiceImplBase {

    /** Valeur stockée et version à laquelle elle a été écrite. */
    private record Entree(String valeur, long version) {
    }

    private final EtatSante etat;
    private final ConcurrentHashMap<String, Entree> donnees = new ConcurrentHashMap<>();
    private final AtomicLong compteurVersion = new AtomicLong(0);

    /** @param etat état de santé partagé du processus */
    public StorageServiceImpl(EtatSante etat) {
        this.etat = etat;
    }

    @Override
    public void put(PutRequest req, StreamObserver<PutResponse> resp) {
        if (!attendre(resp)) {
            return;
        }
        if (req.getKey().isBlank()) {
            resp.onError(Status.INVALID_ARGUMENT.withDescription("La clé ne peut pas être vide").asRuntimeException());
            return;
        }
        long version = compteurVersion.incrementAndGet();
        donnees.put(req.getKey(), new Entree(req.getValue(), version));
        resp.onNext(PutResponse.newBuilder().setStored(true).setVersion(version).build());
        resp.onCompleted();
    }

    @Override
    public void get(GetRequest req, StreamObserver<GetResponse> resp) {
        if (!attendre(resp)) {
            return;
        }
        Entree e = donnees.get(req.getKey());
        GetResponse.Builder b = GetResponse.newBuilder().setFound(e != null);
        if (e != null) {
            b.setValue(e.valeur()).setVersion(e.version());
        }
        resp.onNext(b.build());
        resp.onCompleted();
    }

    @Override
    public void healthCheck(HealthReq req, StreamObserver<HealthResp> resp) {
        etat.repondreHealthCheck(resp);
    }

    /** Applique la latence simulée ; retourne {@code false} (et signale l'erreur) si interrompu. */
    private boolean attendre(StreamObserver<?> resp) {
        try {
            etat.appliquerLatenceSimulee();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.onError(Status.CANCELLED.withDescription("Requête interrompue").asRuntimeException());
            return false;
        }
    }
}
