package ca.usherbrooke.ift605.mesh.services;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import ca.usherbrooke.ift605.mesh.proto.AdminServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.CalcServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.CalcRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.FaultReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.GetRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthReq;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthResp;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.HealthStatus;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.NotifRequest;
import ca.usherbrooke.ift605.mesh.proto.MeshProto.PutRequest;
import ca.usherbrooke.ift605.mesh.proto.NotifServiceGrpc;
import ca.usherbrooke.ift605.mesh.proto.StorageServiceGrpc;
import ca.usherbrooke.ift605.mesh.sonde.Sonde;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests d'intégration : les trois services démarrent sur des ports libres, répondent à leurs
 * opérations et à leur HealthCheck ; la sonde produit SERVING / DEGRADED / DOWN.
 */
class ServicesEtSondeTest {

    private final List<Server> serveurs = new ArrayList<>();
    private final List<ManagedChannel> canaux = new ArrayList<>();
    private int portCalc;
    private int portStorage;
    private int portNotif;

    @BeforeEach
    void demarrer() throws IOException {
        portCalc = portLibre();
        portStorage = portLibre();
        portNotif = portLibre();
        EtatSante etatCalc = new EtatSante("CalcService", portCalc);
        serveurs.add(ServeurMesh.demarrer(etatCalc, new CalcServiceImpl(etatCalc)));
        EtatSante etatStorage = new EtatSante("StorageService", portStorage);
        serveurs.add(ServeurMesh.demarrer(etatStorage, new StorageServiceImpl(etatStorage)));
        EtatSante etatNotif = new EtatSante("NotifService", portNotif);
        serveurs.add(ServeurMesh.demarrer(etatNotif, new NotifServiceImpl(etatNotif)));
    }

    @AfterEach
    void arreter() {
        canaux.forEach(Sonde::fermerCanal);
        serveurs.forEach(Server::shutdownNow);
    }

    @Test
    void calcService() {
        var stub = CalcServiceGrpc.newBlockingStub(canal(portCalc));
        assertEquals(42.0, stub.compute(CalcRequest.newBuilder().setA(6).setB(7).setOp("*").build()).getResult());
        assertEquals(-1.0, stub.compute(CalcRequest.newBuilder().setA(1).setB(2).setOp("-").build()).getResult());
        assertTrue(Double.isNaN(stub.compute(CalcRequest.newBuilder().setA(1).setB(0).setOp("/").build()).getResult()));
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                () -> stub.compute(CalcRequest.newBuilder().setA(1).setB(2).setOp("%").build()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    }

    @Test
    void storageService() {
        var stub = StorageServiceGrpc.newBlockingStub(canal(portStorage));
        long v1 = stub.put(PutRequest.newBuilder().setKey("k").setValue("a").build()).getVersion();
        long v2 = stub.put(PutRequest.newBuilder().setKey("k").setValue("b").build()).getVersion();
        assertTrue(v2 > v1);
        var g = stub.get(GetRequest.newBuilder().setKey("k").build());
        assertTrue(g.getFound());
        assertEquals("b", g.getValue());
        assertEquals(v2, g.getVersion());
        assertFalse(stub.get(GetRequest.newBuilder().setKey("absent").build()).getFound());
        assertThrows(StatusRuntimeException.class, () -> stub.put(PutRequest.newBuilder().setKey(" ").build()));
    }

    @Test
    void notifService() {
        var stub = NotifServiceGrpc.newBlockingStub(canal(portNotif));
        var r = stub.notify(NotifRequest.newBuilder().setEvent("E").setPayload("p").build());
        assertTrue(r.getAccepted());
        assertEquals(1, r.getReceivedCount());
        assertFalse(stub.notify(NotifRequest.newBuilder().setEvent("").build()).getAccepted());
    }

    @Test
    void healthCheckDesTroisServices() {
        HealthReq req = HealthReq.getDefaultInstance();
        HealthResp c = CalcServiceGrpc.newBlockingStub(canal(portCalc)).healthCheck(req);
        HealthResp s = StorageServiceGrpc.newBlockingStub(canal(portStorage)).healthCheck(req);
        HealthResp n = NotifServiceGrpc.newBlockingStub(canal(portNotif)).healthCheck(req);
        for (HealthResp h : List.of(c, s, n)) {
            assertTrue(h.getStatus() == HealthStatus.SERVING || h.getStatus() == HealthStatus.DEGRADED);
            assertTrue(h.getUptimeMs() >= 0);
            assertTrue(h.getCpuLoad() >= 0 && h.getCpuLoad() <= 1);
        }
        assertEquals("StorageService", s.getServiceName());
        assertEquals(portNotif, n.getPort());
    }

    @Test
    void sondeServingPuisDegradedPuisDown() {
        Sonde sonde = new Sonde("NotifService", portNotif, portLibre());
        // Fixe la charge CPU à 0 : au démarrage de la JVM de test, la compilation JIT peut
        // pousser la charge réelle au-delà de 0,90 et rendre le statut DEGRADED.
        AdminServiceGrpc.newBlockingStub(canal(portNotif))
                .injectFault(FaultReq.newBuilder().setCpuLoadOverride(0.0).build());
        MesureSante m1 = sonde.pinger();
        assertEquals(MesureSante.SERVING, m1.statut);
        assertTrue(m1.uptimeMs >= 0);

        // Dégradation simulée : +600 ms par appel → DEGRADED et latence mesurée > 500 ms
        AdminServiceGrpc.newBlockingStub(canal(portNotif))
                .injectFault(FaultReq.newBuilder().setExtraLatencyMs(600).build());
        MesureSante m2 = sonde.pinger();
        assertEquals(MesureSante.DEGRADED, m2.statut);
        assertTrue(m2.latenceMs >= 600, "latence mesurée : " + m2.latenceMs);

        // Surcharge simulée
        AdminServiceGrpc.newBlockingStub(canal(portNotif))
                .injectFault(FaultReq.newBuilder().setExtraLatencyMs(0).setCpuLoadOverride(0.95).build());
        MesureSante m3 = sonde.pinger();
        assertEquals(MesureSante.DEGRADED, m3.statut);
        assertEquals(0.95, m3.cpuLoad, 1e-9);

        // Arrêt du service : la sonde ne plante pas et produit DOWN
        serveurs.get(2).shutdownNow();
        MesureSante m4 = sonde.pinger();
        assertEquals(MesureSante.DOWN, m4.statut);
        sonde.arreter();
    }

    @Test
    void sondeSurPortSansServiceDonneDown() {
        Sonde sonde = new Sonde("CalcService", portLibre(), portLibre());
        assertEquals(MesureSante.DOWN, sonde.pinger().statut);
    }

    @Test
    void envoiIpcUneLigneJson() throws Exception {
        try (ServerSocket faux = new ServerSocket(0)) {
            Sonde sonde = new Sonde("CalcService", portCalc, faux.getLocalPort());
            MesureSante m = sonde.pinger();
            Thread envoi = new Thread(() -> sonde.envoyerIPC(m));
            envoi.start();
            try (Socket s = faux.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
                assertEquals(m, MesureSante.depuisJson(in.readLine()));
            }
            envoi.join();
        }
    }

    @Test
    void envoiIpcSansMonitorNePlantePas() {
        Sonde sonde = new Sonde("CalcService", portCalc, portLibre());
        assertFalse(sonde.envoyerIPC(sonde.pinger()));
    }

    private ManagedChannel canal(int port) {
        ManagedChannel c = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        canaux.add(c);
        return c;
    }

    static int portLibre() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
