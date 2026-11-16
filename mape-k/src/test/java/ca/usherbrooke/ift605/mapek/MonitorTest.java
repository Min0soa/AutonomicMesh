package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import ca.usherbrooke.ift605.mesh.services.EtatSante;
import ca.usherbrooke.ift605.mesh.services.ServeurMesh;
import ca.usherbrooke.ift605.mesh.services.StorageServiceImpl;
import ca.usherbrooke.ift605.mesh.sonde.LanceurSondes;
import ca.usherbrooke.ift605.mesh.sonde.Sonde;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorTest {

    private KnowledgeBase kb;
    private Monitor monitor;

    @BeforeEach
    void demarrer() {
        kb = new KnowledgeBase();
        monitor = new Monitor(kb);
        monitor.demarrerEnArrierePlan(0); // port libre
    }

    @AfterEach
    void arreter() {
        monitor.arreter();
    }

    @Test
    void validation() {
        long now = System.currentTimeMillis();
        assertNull(Monitor.valider(new MesureSante("S", now, "SERVING", 1, 0.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante(null, now, "SERVING", 1, 0.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", now, "OK", 1, 0.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", 0, "SERVING", 1, 0.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", now + 3_600_000, "SERVING", 1, 0.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", now, "SERVING", -1, 0.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", now, "SERVING", 1, 1.5, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", now, "SERVING", 1, Double.NaN, 10)));
        assertNotNull(Monitor.valider(new MesureSante("S", now, "SERVING", 1, 0.5, -10)));
    }

    @Test
    void recoitPlusieursLignesParSocketEtRejetteLesInvalides() throws Exception {
        long now = System.currentTimeMillis();
        try (Socket s = new Socket("localhost", monitor.getPort());
             Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(new MesureSante("CalcService", now, "SERVING", 2, 0.1, 100).versJson() + "\n");
            w.write("pas du json\n");
            w.write("{\"service\":\"CalcService\",\"statut\":\"PEUT-ETRE\",\"timestamp\":1}\n");
            w.write(new MesureSante("NotifService", now, "DOWN", 2000, 0, 0).versJson() + "\n");
            w.flush();
        }
        attendre(() -> monitor.getNbRecues() + monitor.getNbRejetees() == 4);
        assertEquals(2, monitor.getNbRecues());
        assertEquals(2, monitor.getNbRejetees());
        assertEquals(MesureSante.SERVING, kb.getMesures().get("CalcService").statut);
        assertTrue(kb.getMesures().get("NotifService").estDown());
    }

    @Test
    void finDeChaineSondeVersKnowledgeBase() throws Exception {
        int port = portLibre();
        EtatSante etat = new EtatSante("StorageService", port);
        Server serveur = ServeurMesh.demarrer(etat, new StorageServiceImpl(etat));
        Sonde sonde = LanceurSondes.demarrer(new Sonde("StorageService", "localhost", port, "localhost", monitor.getPort()));
        try {
            attendre(() -> kb.getMesure("StorageService").isPresent());
            MesureSante m = kb.getMesure("StorageService").orElseThrow();
            // SERVING, ou DEGRADED si la JVM de test est momentanément chargée (JIT)
            assertFalse(m.estDown(), "le service doit répondre : " + m);
            assertTrue(m.latenceMs >= 0);

            // Le service tombe : la mesure DOWN arrive au cycle suivant (≤ 5 s)
            serveur.shutdownNow();
            attendre(() -> kb.getMesure("StorageService").map(MesureSante::estDown).orElse(false));
            assertTrue(kb.getHistorique("StorageService").size() >= 2);
            assertFalse(kb.getMesures().isEmpty());
        } finally {
            sonde.arreter();
            serveur.shutdownNow();
        }
    }

    private static void attendre(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long limite = System.currentTimeMillis() + 12_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > limite) {
                throw new AssertionError("Condition non atteinte dans le délai");
            }
            Thread.sleep(50);
        }
    }

    private static int portLibre() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
