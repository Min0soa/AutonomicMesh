package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * État de santé d'un processus de service, partagé entre l'implémentation métier, le
 * HealthCheck et le service d'administration (injection de pannes).
 *
 * <p>Règles de calcul du statut renvoyé par {@code HealthCheck} :</p>
 * <ul>
 *   <li>{@code DOWN} : le serveur est en cours d'arrêt (crochet d'arrêt JVM) ;</li>
 *   <li>{@code DEGRADED} : charge CPU &gt; {@value #SEUIL_CPU_DEGRADE} ou latence simulée active ;</li>
 *   <li>{@code SERVING} : sinon.</li>
 * </ul>
 * <p>Un service totalement arrêté ne peut évidemment pas répondre : c'est alors la sonde
 * qui produit la mesure DOWN.</p>
 *
 * <p>Thread-safe : les champs modifiables sont atomiques ou {@code volatile}.</p>
 */
public final class EtatSante {

    /** Seuil de charge CPU au-delà duquel le service se déclare DEGRADED. */
    public static final double SEUIL_CPU_DEGRADE = 0.90;

    private final String nomService;
    private final int port;
    private final long debutMs = System.currentTimeMillis();
    private final AtomicLong latenceAjouteeMs = new AtomicLong(0);
    private volatile double cpuForce = -1.0;
    private volatile boolean enArret = false;
    private final com.sun.management.OperatingSystemMXBean os = chargerBeanOs();

    /**
     * @param nomService nom logique du service (ex. « CalcService »)
     * @param port       port gRPC de l'instance
     */
    public EtatSante(String nomService, int port) {
        this.nomService = nomService;
        this.port = port;
    }

    private static com.sun.management.OperatingSystemMXBean chargerBeanOs() {
        try {
            return ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        } catch (RuntimeException | LinkageError e) {
            return null; // JVM sans com.sun.management : la charge CPU sera rapportée à 0
        }
    }

    /** @return nom logique du service */
    public String nomService() {
        return nomService;
    }

    /** @return port gRPC de l'instance */
    public int port() {
        return port;
    }

    /** @return millisecondes écoulées depuis le démarrage du service */
    public long uptimeMs() {
        return System.currentTimeMillis() - debutMs;
    }

    /**
     * @return charge CPU du processus entre 0.0 et 1.0 (valeur simulée si une surcharge a été
     *         injectée)
     */
    public double chargeCpu() {
        double force = cpuForce;
        if (force >= 0) {
            return Math.min(force, 1.0);
        }
        if (os == null) {
            return 0.0;
        }
        double charge = os.getProcessCpuLoad();
        return (charge < 0 || Double.isNaN(charge)) ? 0.0 : Math.min(charge, 1.0);
    }

    /** @return statut courant selon les règles décrites dans la Javadoc de la classe */
    public HealthStatus statutCourant() {
        if (enArret) {
            return HealthStatus.DOWN;
        }
        if (chargeCpu() > SEUIL_CPU_DEGRADE || latenceAjouteeMs.get() > 0) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.SERVING;
    }

    /** @return la réponse HealthCheck correspondant à l'état actuel */
    public HealthResp construireReponse() {
        return HealthResp.newBuilder()
                .setStatus(statutCourant())
                .setUptimeMs(uptimeMs())
                .setCpuLoad(chargeCpu())
                .setServiceName(nomService)
                .setPort(port)
                .build();
    }

    /**
     * Applique la latence simulée (si une dégradation a été injectée). Appelée au début de
     * chaque RPC, métier ou HealthCheck.
     *
     * @throws InterruptedException si le thread gRPC est interrompu pendant l'attente
     */
    public void appliquerLatenceSimulee() throws InterruptedException {
        long delai = latenceAjouteeMs.get();
        if (delai > 0) {
            Thread.sleep(delai);
        }
    }

    /**
     * Implémentation commune du RPC {@code HealthCheck} des trois services.
     *
     * @param reponse observateur gRPC de la réponse
     */
    public void repondreHealthCheck(StreamObserver<HealthResp> reponse) {
        try {
            appliquerLatenceSimulee();
            reponse.onNext(construireReponse());
            reponse.onCompleted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reponse.onError(Status.CANCELLED.withDescription("HealthCheck interrompu").asRuntimeException());
        }
    }

    /**
     * Injecte une dégradation (utilisé par {@link AdminServiceImpl}).
     *
     * @param latenceMs délai ajouté à chaque appel (0 pour annuler)
     * @param cpu       charge CPU simulée ; valeur négative pour revenir à la mesure réelle
     */
    public void injecterPanne(long latenceMs, double cpu) {
        latenceAjouteeMs.set(Math.max(0, latenceMs));
        cpuForce = cpu;
    }

    /** @return latence actuellement simulée (ms) */
    public long latenceAjouteeMs() {
        return latenceAjouteeMs.get();
    }

    /** Marque le service comme en cours d'arrêt : les HealthCheck suivants renvoient DOWN. */
    public void marquerEnArret() {
        enArret = true;
    }
}
