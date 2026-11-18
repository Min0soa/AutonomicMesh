package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompteurPannesTest {

    @Test
    void compterPremierePanne() {

        KnowledgeBase kb = new KnowledgeBase();
        Analyzer analyzer = new Analyzer(kb);

        kb.majMesure("CalcService",
                MesureSante.down("CalcService", 2000));

        analyzer.analyser();

        assertEquals(1, kb.getNbPannes("CalcService"));
    }

    @Test
    void nePasCompterPlusieursFoisLaMemePanne() {

        KnowledgeBase kb = new KnowledgeBase();
        Analyzer analyzer = new Analyzer(kb);

        kb.majMesure("CalcService",
                MesureSante.down("CalcService", 2000));

        analyzer.analyser();
        analyzer.analyser();
        analyzer.analyser();

        assertEquals(1, kb.getNbPannes("CalcService"));
    }

    @Test
    void compterNouvellePanneApresRecuperation() {

        KnowledgeBase kb = new KnowledgeBase();
        Analyzer analyzer = new Analyzer(kb);

        // Premiere panne
        kb.majMesure("CalcService",
                MesureSante.down("CalcService", 2000));

        analyzer.analyser();

        assertEquals(1, kb.getNbPannes("CalcService"));

        // Recuperation du service
        kb.majMesure("CalcService",
                new MesureSante(
                        "CalcService",
                        System.currentTimeMillis(),
                        MesureSante.SERVING,
                        50,
                        0.20,
                        10000
                ));

        analyzer.analyser();

        assertEquals(1, kb.getNbPannes("CalcService"));

        // Deuxieme panne
        kb.majMesure("CalcService",
                MesureSante.down("CalcService", 2000));

        analyzer.analyser();

        assertEquals(2, kb.getNbPannes("CalcService"));
    }
}