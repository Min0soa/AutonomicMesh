package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultResp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.logging.Logger;

/**
 * Service d'administration hébergé par chaque processus de service. Il permet de provoquer
 * une <b>dégradation contrôlée</b> (latence ajoutée, surcharge CPU simulée) afin de tester
 * les règles DEGRADATION et SURCHARGE de l'Analyzer sans tuer le processus.
 *
 * <p>Exemple : {@code ClientTest degrader CalcService 800} → chaque appel prend +800 ms.</p>
 */
public class AdminServiceImpl extends AdminServiceGrpc.AdminServiceImplBase {

    private static final Logger LOG = Journal.logger("Admin");

    private final EtatSante etat;

    /** @param etat état de santé partagé du processus */
    public AdminServiceImpl(EtatSante etat) {
        this.etat = etat;
    }

    @Override
    public void injectFault(FaultReq req, StreamObserver<FaultResp> resp) {
        if (req.getExtraLatencyMs() < 0) {
            resp.onError(Status.INVALID_ARGUMENT.withDescription("extraLatencyMs doit être ≥ 0").asRuntimeException());
            return;
        }
        double cpu = req.hasCpuLoadOverride() ? req.getCpuLoadOverride() : -1.0;
        if (cpu > 1.0) {
            resp.onError(Status.INVALID_ARGUMENT.withDescription("cpuLoadOverride doit être ≤ 1.0").asRuntimeException());
            return;
        }
        etat.injecterPanne(req.getExtraLatencyMs(), cpu);
        String msg = (req.getExtraLatencyMs() == 0 && cpu < 0)
                ? etat.nomService() + " rétabli (aucune dégradation simulée)"
                : String.format(java.util.Locale.ROOT, "%s dégradé : +%d ms par appel, cpu %s",
                        etat.nomService(), req.getExtraLatencyMs(), cpu < 0 ? "réel" : String.valueOf(cpu));
        LOG.warning(msg);
        resp.onNext(FaultResp.newBuilder().setApplied(true).setMessage(msg).build());
        resp.onCompleted();
    }
}
