package ca.usherbrooke.ift605.mapek;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientAdaptatifTest {

    @Test
    void utiliserPortParDefaut() {

        KnowledgeBase kb = new KnowledgeBase();

        ClientAdaptatif client = new ClientAdaptatif(kb);

        assertEquals(
                50051,
                client.obtenirPort("CalcService")
        );
    }

    @Test
    void utiliserNouvelleRoute() {

        KnowledgeBase kb = new KnowledgeBase();

        ClientAdaptatif client = new ClientAdaptatif(kb);

        kb.setRoute(
                "CalcService",
                "CalcService#50061"
        );

        assertEquals(
                50061,
                client.obtenirPort("CalcService")
        );
    }

    @Test
    void revenirAuPortPrincipal() {

        KnowledgeBase kb = new KnowledgeBase();

        ClientAdaptatif client = new ClientAdaptatif(kb);

        kb.setRoute(
                "CalcService",
                "CalcService#50061"
        );

        kb.setRoute("CalcService", null);

        assertEquals(
                50051,
                client.obtenirPort("CalcService")
        );
    }
}