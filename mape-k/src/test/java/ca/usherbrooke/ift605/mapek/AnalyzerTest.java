package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de la classe Analyzer.
 */
public class AnalyzerTest {

    // Test 1 : detection d'une panne totale
    @Test
    void detecterPanneTotale() {

        KnowledgeBase kb = new KnowledgeBase();

        MesureSante mesure = new MesureSante(
                "CalcService",
                System.currentTimeMillis(),
                MesureSante.DOWN,
                2000,
                0.0,
                0
        );

        kb.majMesure("CalcService", mesure);

        Analyzer analyzer = new Analyzer(kb);
        List<Anomalie> anomalies = analyzer.analyser();

        assertEquals(1, anomalies.size());
        assertEquals("CalcService", anomalies.get(0).getService());
        assertEquals(TypeAnomalie.PANNE_TOTALE,
                anomalies.get(0).getType());
    }

    // Test 2 : detection d'une degradation
    @Test
    void detecterDegradation() {

        KnowledgeBase kb = new KnowledgeBase();

        MesureSante mesure = new MesureSante(
                "CalcService",
                System.currentTimeMillis(),
                MesureSante.DEGRADED,
                800,
                0.20,
                60000
        );

        kb.majMesure("CalcService", mesure);

        Analyzer analyzer = new Analyzer(kb);
        List<Anomalie> anomalies = analyzer.analyser();

        assertEquals(1, anomalies.size());
        assertEquals("CalcService", anomalies.get(0).getService());
        assertEquals(TypeAnomalie.DEGRADATION,
                anomalies.get(0).getType());
    }

    // Test 3 : detection d'une surcharge CPU
    @Test
    void detecterSurcharge() {

        KnowledgeBase kb = new KnowledgeBase();

        MesureSante mesure = new MesureSante(
                "StorageService",
                System.currentTimeMillis(),
                MesureSante.DEGRADED,
                30,
                0.95,
                60000
        );

        kb.majMesure("StorageService", mesure);

        Analyzer analyzer = new Analyzer(kb);
        List<Anomalie> anomalies = analyzer.analyser();

        assertEquals(1, anomalies.size());
        assertEquals("StorageService", anomalies.get(0).getService());
        assertEquals(TypeAnomalie.SURCHARGE,
                anomalies.get(0).getType());
    }

    // Test 4 : absence d'anomalie pour un service normal
    @Test
    void nePasDetecterAnomalieServiceNormal() {

        KnowledgeBase kb = new KnowledgeBase();

        MesureSante mesure = new MesureSante(
                "NotifService",
                System.currentTimeMillis(),
                MesureSante.SERVING,
                20,
                0.10,
                60000
        );

        kb.majMesure("NotifService", mesure);

        Analyzer analyzer = new Analyzer(kb);
        List<Anomalie> anomalies = analyzer.analyser();

        assertTrue(anomalies.isEmpty());
    }

    // Test 5 : analyse d'une KnowledgeBase vide
    @Test
    void analyserKnowledgeBaseVide() {

        KnowledgeBase kb = new KnowledgeBase();

        Analyzer analyzer = new Analyzer(kb);
        List<Anomalie> anomalies = analyzer.analyser();

        assertTrue(anomalies.isEmpty());
    }

    // Test 6 : detection de plusieurs anomalies
    @Test
    void detecterPlusieursAnomalies() {

        KnowledgeBase kb = new KnowledgeBase();

        kb.majMesure("CalcService", new MesureSante(
                "CalcService",
                System.currentTimeMillis(),
                MesureSante.DOWN,
                2000,
                0.0,
                0
        ));

        kb.majMesure("StorageService", new MesureSante(
                "StorageService",
                System.currentTimeMillis(),
                MesureSante.DEGRADED,
                800,
                0.20,
                60000
        ));

        kb.majMesure("NotifService", new MesureSante(
                "NotifService",
                System.currentTimeMillis(),
                MesureSante.DEGRADED,
                20,
                0.95,
                60000
        ));

        Analyzer analyzer = new Analyzer(kb);
        List<Anomalie> anomalies = analyzer.analyser();

        assertEquals(3, anomalies.size());

        assertTrue(anomalies.stream().anyMatch(a ->
                a.getService().equals("CalcService")
                && a.getType() == TypeAnomalie.PANNE_TOTALE));

        assertTrue(anomalies.stream().anyMatch(a ->
                a.getService().equals("StorageService")
                && a.getType() == TypeAnomalie.DEGRADATION));

        assertTrue(anomalies.stream().anyMatch(a ->
                a.getService().equals("NotifService")
                && a.getType() == TypeAnomalie.SURCHARGE));
    }
}