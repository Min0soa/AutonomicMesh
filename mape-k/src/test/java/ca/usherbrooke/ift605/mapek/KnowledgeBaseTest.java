package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseTest {

    private static MesureSante mesure(String service, long ts, String statut) {
        return new MesureSante(service, ts, statut, 5, 0.1, 1000);
    }

    @Test
    void nouvelleMesureRemplaceEtatCourant() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.majMesure("CalcService", mesure("CalcService", 1, MesureSante.SERVING));
        kb.majMesure("CalcService", mesure("CalcService", 2, MesureSante.DOWN));
        assertEquals(MesureSante.DOWN, kb.getMesures().get("CalcService").statut);
        assertEquals(2, kb.getHistorique("CalcService").size());
    }

    @Test
    void mesureEnRetardNeRemplacePasEtatCourant() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.majMesure("S", mesure("S", 10, MesureSante.DOWN));
        kb.majMesure("S", mesure("S", 5, MesureSante.SERVING));
        assertEquals(MesureSante.DOWN, kb.getMesure("S").orElseThrow().statut);
    }

    @Test
    void historiqueBorne() {
        KnowledgeBase kb = new KnowledgeBase(3);
        for (int i = 1; i <= 10; i++) {
            kb.majMesure("S", mesure("S", i, MesureSante.SERVING));
        }
        List<MesureSante> h = kb.getHistorique("S");
        assertEquals(3, h.size());
        assertEquals(8, h.get(0).timestamp);
        assertEquals(10, h.get(2).timestamp);
    }

    @Test
    void getMesuresEstUneCopie() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.majMesure("A", mesure("A", 1, MesureSante.SERVING));
        var copie = kb.getMesures();
        kb.majMesure("B", mesure("B", 1, MesureSante.SERVING));
        assertEquals(1, copie.size());
        assertThrows(UnsupportedOperationException.class, () -> copie.put("X", null));
    }

    @Test
    void routage() {
        KnowledgeBase kb = new KnowledgeBase();
        assertEquals("CalcService", kb.getRoute("CalcService"), "route directe par défaut");
        kb.setRoute("CalcService", "CalcService#50061");
        assertEquals("CalcService#50061", kb.getRoute("CalcService"));
        assertEquals(1, kb.getRoutes().size());
        kb.setRoute("CalcService", null);
        assertEquals("CalcService", kb.getRoute("CalcService"));
        assertTrue(kb.getRoutes().isEmpty());
    }

    @Test
    void compteurPannesThreadSafe() throws InterruptedException {
        KnowledgeBase kb = new KnowledgeBase();
        int threads = 8;
        int parThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch depart = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    depart.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < parThread; i++) {
                    kb.incrementerPannes("CalcService");
                }
            });
        }
        depart.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(threads * parThread, kb.getNbPannes("CalcService"));
        assertEquals(0, kb.getNbPannes("Inconnu"));
    }

    @Test
    void ecrituresConcurrentesEtLectures() throws InterruptedException {
        KnowledgeBase kb = new KnowledgeBase(20);
        List<Thread> threads = new ArrayList<>();
        for (String s : List.of("CalcService", "StorageService", "NotifService")) {
            threads.add(new Thread(() -> {
                for (int i = 1; i <= 2000; i++) {
                    kb.majMesure(s, mesure(s, i, MesureSante.SERVING));
                }
            }));
        }
        threads.add(new Thread(() -> {
            for (int i = 0; i < 2000; i++) {
                kb.getMesures().values().forEach(m -> assertTrue(m.timestamp > 0));
                kb.getHistorique("CalcService").size();
            }
        }));
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(3, kb.getServices().size());
        assertEquals(2000, kb.getMesure("NotifService").orElseThrow().timestamp);
        assertEquals(20, kb.getHistorique("StorageService").size());
    }
}
