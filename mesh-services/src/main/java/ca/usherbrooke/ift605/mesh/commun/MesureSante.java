package ca.usherbrooke.ift605.mesh.commun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.util.Objects;
import java.util.Set;

/**
 * Mesure de santé d'un service, produite par une {@code Sonde} et transmise au
 * {@code Monitor} en JSON (une mesure par ligne) sur le canal IPC.
 *
 * <p>Format convenu (interface commune) :</p>
 * <pre>{@code
 * {"service":"CalcService","timestamp":1763250243512,"statut":"SERVING",
 *  "latenceMs":3,"cpuLoad":0.04,"uptimeMs":125034}
 * }</pre>
 *
 * <p>La classe est <b>immuable</b> (champs {@code public final}) : elle peut être partagée
 * sans risque entre les threads du Monitor et de la boucle MAPE-K. Les champs publics
 * permettent l'accès direct utilisé dans les squelettes ({@code m.statut}, {@code m.latenceMs}).</p>
 */
public final class MesureSante {

    /** Le service répond normalement. */
    public static final String SERVING = "SERVING";
    /** Le service répond mais est ralenti ou surchargé. */
    public static final String DEGRADED = "DEGRADED";
    /** Le service est injoignable ou en arrêt. */
    public static final String DOWN = "DOWN";
    /** Ensemble des statuts valides. */
    public static final Set<String> STATUTS = Set.of(SERVING, DEGRADED, DOWN);

    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    /** Nom du service (ou de l'instance) mesuré, ex. {@code "CalcService"}. */
    public final String service;
    /** Instant de la mesure, en millisecondes depuis l'époque Unix. */
    public final long timestamp;
    /** {@link #SERVING}, {@link #DEGRADED} ou {@link #DOWN}. */
    public final String statut;
    /** Latence aller-retour du HealthCheck gRPC, en millisecondes. */
    public final long latenceMs;
    /** Charge CPU du processus du service, entre 0.0 et 1.0 (0 si DOWN). */
    public final double cpuLoad;
    /** Durée de fonctionnement du service, en millisecondes (0 si DOWN). */
    public final long uptimeMs;

    /**
     * Crée une mesure.
     *
     * @param service   nom du service
     * @param timestamp instant de la mesure (ms epoch)
     * @param statut    SERVING, DEGRADED ou DOWN
     * @param latenceMs latence mesurée (ms)
     * @param cpuLoad   charge CPU [0, 1]
     * @param uptimeMs  uptime du service (ms)
     */
    public MesureSante(String service, long timestamp, String statut,
                       long latenceMs, double cpuLoad, long uptimeMs) {
        this.service = service;
        this.timestamp = timestamp;
        this.statut = statut;
        this.latenceMs = latenceMs;
        this.cpuLoad = cpuLoad;
        this.uptimeMs = uptimeMs;
    }

    /**
     * Fabrique une mesure DOWN (service injoignable), produite par la sonde quand l'appel
     * gRPC échoue.
     *
     * @param service   nom du service
     * @param latenceMs temps écoulé avant l'échec de l'appel (ms)
     * @return la mesure DOWN horodatée maintenant
     */
    public static MesureSante down(String service, long latenceMs) {
        return new MesureSante(service, System.currentTimeMillis(), DOWN, latenceMs, 0.0, 0L);
    }

    /** @return {@code true} si le statut est DOWN */
    public boolean estDown() {
        return DOWN.equals(statut);
    }

    /** @return la mesure sérialisée en JSON sur une seule ligne */
    public String versJson() {
        return GSON.toJson(this);
    }

    /**
     * Désérialise une mesure JSON. Aucune validation métier n'est faite ici (voir
     * {@code Monitor.valider}) : les champs absents prennent leur valeur par défaut.
     *
     * @param json texte JSON
     * @return la mesure lue
     * @throws JsonParseException si le texte n'est pas un objet JSON valide
     */
    public static MesureSante depuisJson(String json) {
        MesureSante m = GSON.fromJson(json, MesureSante.class);
        if (m == null) {
            throw new JsonParseException("JSON vide");
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MesureSante m)) {
            return false;
        }
        return timestamp == m.timestamp && latenceMs == m.latenceMs
                && Double.compare(cpuLoad, m.cpuLoad) == 0 && uptimeMs == m.uptimeMs
                && Objects.equals(service, m.service) && Objects.equals(statut, m.statut);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, timestamp, statut, latenceMs, cpuLoad, uptimeMs);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%s %s latence=%dms cpu=%.2f uptime=%ds",
                service, statut, latenceMs, cpuLoad, uptimeMs / 1000);
    }
}
