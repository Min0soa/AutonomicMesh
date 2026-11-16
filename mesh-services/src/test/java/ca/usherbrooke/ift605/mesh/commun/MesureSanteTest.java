package ca.usherbrooke.ift605.mesh.commun;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MesureSanteTest {

    @Test
    void allerRetourJson() {
        MesureSante m = new MesureSante("CalcService", 1_763_250_243_512L, MesureSante.SERVING, 3, 0.04, 125_034);
        String json = m.versJson();
        assertFalse(json.contains("\n"), "une mesure = une seule ligne");
        assertTrue(json.contains("\"statut\":\"SERVING\""));
        assertEquals(m, MesureSante.depuisJson(json));
    }

    @Test
    void formatConvenuLisible() {
        String json = "{\"service\":\"NotifService\",\"timestamp\":1,\"statut\":\"DOWN\","
                + "\"latenceMs\":2001,\"cpuLoad\":0.0,\"uptimeMs\":0}";
        MesureSante m = MesureSante.depuisJson(json);
        assertEquals("NotifService", m.service);
        assertTrue(m.estDown());
        assertEquals(2001, m.latenceMs);
    }

    @Test
    void mesureDown() {
        MesureSante m = MesureSante.down("StorageService", 12);
        assertEquals(MesureSante.DOWN, m.statut);
        assertEquals(0.0, m.cpuLoad);
        assertTrue(m.timestamp > 0);
    }

    @Test
    void parNomAccepteLesVariantes() {
        assertEquals(ServiceMesh.CALC, ServiceMesh.parNom("CalcService"));
        assertEquals(ServiceMesh.CALC, ServiceMesh.parNom("calc"));
        assertEquals(ServiceMesh.STORAGE, ServiceMesh.parNom("StorageService#50062"));
        assertEquals(ServiceMesh.NOTIF, ServiceMesh.parNom("notifservice:50063"));
        assertThrows(IllegalArgumentException.class, () -> ServiceMesh.parNom("Inconnu"));
    }

    @Test
    void commandeLancement() {
        var cmd = ServiceMesh.STORAGE.commandeLancement(50062);
        assertTrue(cmd.get(0).endsWith("java"));
        assertEquals("-cp", cmd.get(1));
        assertEquals("ca.usherbrooke.ift605.mesh.services.StorageServer", cmd.get(3));
        assertEquals("50062", cmd.get(4));
    }
}
