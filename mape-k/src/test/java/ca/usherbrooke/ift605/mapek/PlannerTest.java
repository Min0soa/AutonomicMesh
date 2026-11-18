package ca.usherbrooke.ift605.mapek;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de la classe Planner.
 */
public class PlannerTest {

    // Test 1 : planifier un redemarrage
    @Test
    void planifierRedemarrage() {

        Planner planner = new Planner();

        Anomalie anomalie = new Anomalie(
                "CalcService",
                TypeAnomalie.PANNE_TOTALE
        );

        List<Action> actions = planner.planifier(
                List.of(anomalie)
        );

        assertEquals(1, actions.size());
        assertEquals("CalcService", actions.get(0).getService());
        assertEquals(TypeAction.REDEMARRER,
                actions.get(0).getType());
    }

    // Test 2 : planifier un reacheminement
    @Test
    void planifierReacheminement() {

        Planner planner = new Planner();

        Anomalie anomalie = new Anomalie(
                "StorageService",
                TypeAnomalie.DEGRADATION
        );

        List<Action> actions = planner.planifier(
                List.of(anomalie)
        );

        assertEquals(1, actions.size());
        assertEquals("StorageService", actions.get(0).getService());
        assertEquals(TypeAction.REACHEMINEMENT,
                actions.get(0).getType());
    }

    // Test 3 : planifier une mise a l'echelle
    @Test
    void planifierMiseAEchelle() {

        Planner planner = new Planner();

        Anomalie anomalie = new Anomalie(
                "NotifService",
                TypeAnomalie.SURCHARGE
        );

        List<Action> actions = planner.planifier(
                List.of(anomalie)
        );

        assertEquals(1, actions.size());
        assertEquals("NotifService", actions.get(0).getService());
        assertEquals(TypeAction.MISE_A_ECHELLE,
                actions.get(0).getType());
    }

    // Test 4 : aucune anomalie
    @Test
    void nePasPlanifierActionSansAnomalie() {

        Planner planner = new Planner();

        List<Action> actions = planner.planifier(
                List.of()
        );

        assertTrue(actions.isEmpty());
    }

    // Test 5 : planifier plusieurs actions
    @Test
    void planifierPlusieursActions() {

        Planner planner = new Planner();

        List<Anomalie> anomalies = List.of(

                new Anomalie(
                        "CalcService",
                        TypeAnomalie.PANNE_TOTALE
                ),

                new Anomalie(
                        "StorageService",
                        TypeAnomalie.DEGRADATION
                ),

                new Anomalie(
                        "NotifService",
                        TypeAnomalie.SURCHARGE
                )
        );

        List<Action> actions = planner.planifier(anomalies);

        assertEquals(3, actions.size());

        assertEquals("CalcService", actions.get(0).getService());
        assertEquals(TypeAction.REDEMARRER,
                actions.get(0).getType());

        assertEquals("StorageService", actions.get(1).getService());
        assertEquals(TypeAction.REACHEMINEMENT,
                actions.get(1).getType());

        assertEquals("NotifService", actions.get(2).getService());
        assertEquals(TypeAction.MISE_A_ECHELLE,
                actions.get(2).getType());
    }
}