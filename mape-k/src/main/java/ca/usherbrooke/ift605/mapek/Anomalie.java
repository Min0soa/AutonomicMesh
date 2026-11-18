package ca.usherbrooke.ift605.mapek;

/**
 * Représente une anomalie détectée sur un service.
 */
public class Anomalie {

    private final String service;
    private final TypeAnomalie type;

    public Anomalie(String service, TypeAnomalie type) {
        this.service = service;
        this.type = type;
    }

    public String getService() {
        return service;
    }

    public TypeAnomalie getType() {
        return type;
    }
}