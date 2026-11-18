package ca.usherbrooke.ift605.mapek;

/**
 * Represente une action corrective a appliquer sur un service.
 */
public class Action {

    private final String service;
    private final TypeAction type;

    public Action(String service, TypeAction type) {
        this.service = service;
        this.type = type;
    }

    public String getService() {
        return service;
    }

    public TypeAction getType() {
        return type;
    }
}