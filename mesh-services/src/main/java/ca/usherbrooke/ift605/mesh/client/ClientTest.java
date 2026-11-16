package ca.usherbrooke.ift605.mesh.client;

import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;
import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifResponse;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutResponse;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import ca.usherbrooke.ift605.mesh.sonde.Sonde;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Client gRPC de test : appelle l'opération métier et le HealthCheck de chacun des trois
 * services, et permet d'injecter une dégradation pour tester la boucle MAPE-K.
 *
 * <pre>
 * Usage :
 *   ClientTest [tester] [hôte]                       appelle les 3 services (défaut : localhost)
 *   ClientTest degrader &lt;Service[:port]&gt; &lt;latenceMs&gt; [cpuLoad]
 *                                                   ex. degrader CalcService 800
 *                                                       degrader NotifService 0 0.95
 *   ClientTest retablir &lt;Service[:port]&gt;              annule la dégradation simulée
 * </pre>
 *
 * <p>Code de sortie : 0 si tous les appels réussissent, 1 sinon.</p>
 */
public final class ClientTest {

    private static final long DELAI_APPEL_S = 3;

    private ClientTest() {
    }

    /**
     * @param args voir la Javadoc de la classe
     */
    public static void main(String[] args) {
        String commande = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "tester";
        int code;
        try {
            code = switch (commande) {
                case "tester" -> tester(args.length > 1 ? args[1] : ServiceMesh.HOTE_DEFAUT);
                case "degrader" -> {
                    if (args.length < 3) {
                        yield usage();
                    }
                    double cpu = args.length > 3 ? Double.parseDouble(args[3]) : -1.0;
                    yield injecter(args[1], Long.parseLong(args[2]), cpu);
                }
                case "retablir" -> args.length < 2 ? usage() : injecter(args[1], 0, -1.0);
                default -> usage();
            };
        } catch (IllegalArgumentException e) {
            System.err.println("Erreur : " + e.getMessage());
            code = usage();
        }
        System.exit(code);
    }

    /**
     * Appelle l'opération métier puis le HealthCheck de chaque service.
     *
     * @param hote hôte des services
     * @return 0 si tout a réussi, 1 sinon
     */
    public static int tester(String hote) {
        int echecs = 0;
        System.out.println("=== Test des 3 micro-services AutonomicMesh sur " + hote + " ===");

        // 1) CalcService
        ManagedChannel canal = ouvrir(hote, ServiceMesh.CALC.portParDefaut());
        try {
            var stub = CalcServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS);
            CalcResponse r = stub.compute(CalcRequest.newBuilder().setA(6).setB(7).setOp("*").build());
            System.out.printf(Locale.ROOT, "[OK]    CalcService.Compute(6 * 7)          = %.1f  (traitement %d ms)%n",
                    r.getResult(), r.getLatencyMs());
            HealthResp h = CalcServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS)
                    .healthCheck(HealthReq.newBuilder().setServiceName("CalcService").build());
            afficherSante("CalcService", h);
        } catch (StatusRuntimeException e) {
            echecs++;
            System.out.println("[ÉCHEC] CalcService : " + e.getStatus());
        } finally {
            fermer(canal);
        }

        // 2) StorageService
        canal = ouvrir(hote, ServiceMesh.STORAGE.portParDefaut());
        try {
            var stub = StorageServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS);
            PutResponse p = stub.put(PutRequest.newBuilder().setKey("projet").setValue("AutonomicMesh").build());
            GetResponse g = stub.get(GetRequest.newBuilder().setKey("projet").build());
            System.out.printf("[OK]    StorageService.Put/Get(\"projet\")    = \"%s\" (version %d, trouvé=%b)%n",
                    g.getValue(), p.getVersion(), g.getFound());
            if (!g.getFound() || !"AutonomicMesh".equals(g.getValue())) {
                echecs++;
                System.out.println("[ÉCHEC] StorageService : la valeur relue ne correspond pas");
            }
            HealthResp h = StorageServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS)
                    .healthCheck(HealthReq.newBuilder().setServiceName("StorageService").build());
            afficherSante("StorageService", h);
        } catch (StatusRuntimeException e) {
            echecs++;
            System.out.println("[ÉCHEC] StorageService : " + e.getStatus());
        } finally {
            fermer(canal);
        }

        // 3) NotifService
        canal = ouvrir(hote, ServiceMesh.NOTIF.portParDefaut());
        try {
            var stub = NotifServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS);
            NotifResponse n = stub.notify(NotifRequest.newBuilder()
                    .setEvent("TEST_CLIENT").setPayload("Bonjour du client de test").build());
            System.out.printf("[OK]    NotifService.Notify(TEST_CLIENT)     accepté=%b (total reçus %d)%n",
                    n.getAccepted(), n.getReceivedCount());
            HealthResp h = NotifServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS)
                    .healthCheck(HealthReq.newBuilder().setServiceName("NotifService").build());
            afficherSante("NotifService", h);
        } catch (StatusRuntimeException e) {
            echecs++;
            System.out.println("[ÉCHEC] NotifService : " + e.getStatus());
        } finally {
            fermer(canal);
        }

        System.out.println(echecs == 0 ? "=== Les 3 services ont répondu correctement ==="
                : "=== " + echecs + " service(s) en échec ===");
        return echecs == 0 ? 0 : 1;
    }

    /**
     * Injecte (ou annule) une dégradation simulée sur un service via {@code AdminService}.
     *
     * @param cible     {@code NomService} ou {@code NomService:port}
     * @param latenceMs latence ajoutée (0 pour aucune)
     * @param cpu       charge CPU simulée, ou négatif pour la valeur réelle
     * @return 0 si la commande a été appliquée, 1 sinon
     */
    public static int injecter(String cible, long latenceMs, double cpu) {
        ServiceMesh type = ServiceMesh.parNom(cible);
        int port = cible.contains(":") ? Integer.parseInt(cible.substring(cible.indexOf(':') + 1))
                : type.portParDefaut();
        ManagedChannel canal = ouvrir(ServiceMesh.HOTE_DEFAUT, port);
        try {
            FaultReq.Builder req = FaultReq.newBuilder().setExtraLatencyMs(latenceMs);
            if (cpu >= 0) {
                req.setCpuLoadOverride(cpu);
            }
            FaultResp r = AdminServiceGrpc.newBlockingStub(canal).withDeadlineAfter(DELAI_APPEL_S, TimeUnit.SECONDS)
                    .injectFault(req.build());
            System.out.println("[OK]    " + r.getMessage());
            return 0;
        } catch (StatusRuntimeException e) {
            System.out.println("[ÉCHEC] " + type.nom() + " (port " + port + ") : " + e.getStatus());
            return 1;
        } finally {
            fermer(canal);
        }
    }

    private static void afficherSante(String nom, HealthResp h) {
        System.out.printf(Locale.ROOT, "        %s.HealthCheck                 statut=%s uptime=%ds cpu=%.2f%n",
                nom, h.getStatus(), h.getUptimeMs() / 1000, h.getCpuLoad());
    }

    private static ManagedChannel ouvrir(String hote, int port) {
        return ManagedChannelBuilder.forAddress(hote, port).usePlaintext().build();
    }

    private static void fermer(ManagedChannel canal) {
        Sonde.fermerCanal(canal);
    }

    private static int usage() {
        System.err.println("""
                Usage :
                  ClientTest [tester] [hôte]
                  ClientTest degrader <Service[:port]> <latenceMs> [cpuLoad]
                  ClientTest retablir <Service[:port]>
                Services : CalcService (50051), StorageService (50052), NotifService (50053)""");
        return 2;
    }
}
