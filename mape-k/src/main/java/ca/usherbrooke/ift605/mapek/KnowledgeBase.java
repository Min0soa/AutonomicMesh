package ca.usherbrooke.ift605.mapek;

import ca.usherbrooke.ift605.mesh.commun.MesureSante;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base de connaissances (le « K » de MAPE-K) : état partagé entre le Monitor (écriture des
 * mesures) et les phases Analyze / Plan / Execute (lecture, compteur de pannes, routage).
 *
 * <p><b>Interface commune convenue</b> (ne pas modifier sans en parler à l'autre partie) :</p>
 * <ul>
 *   <li>{@link #getMesures()} — dernière mesure de chaque service ;</li>
 *   <li>{@link #majMesure(String, MesureSante)} — appelée par le Monitor ;</li>
 *   <li>{@link #getNbPannes(String)}, {@link #incrementerPannes(String)} — compteur de pannes ;</li>
 *   <li>{@link #getRoute(String)}, {@link #setRoute(String, String)} — table de routage.</li>
 * </ul>
 *
 * <p><b>Thread-safety :</b> les trois tables sont des {@link ConcurrentHashMap} et les mises à
 * jour utilisent des opérations atomiques ({@code compute}, {@code merge}). Les méthodes de
 * lecture retournent des <i>copies</i> immuables : l'appelant peut les parcourir sans risque
 * pendant que le Monitor continue d'écrire.</p>
 */
public class KnowledgeBase {

    /** Nombre de mesures conservées par service dans l'historique. */
    public static final int TAILLE_HISTORIQUE_DEFAUT = 50;

    private final ConcurrentHashMap<String, MesureSante> mesures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> nbPannes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> routage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<MesureSante>> historique = new ConcurrentHashMap<>();
    private final int tailleHistorique;

    /** Crée une base avec un historique de {@value #TAILLE_HISTORIQUE_DEFAUT} mesures par service. */
    public KnowledgeBase() {
        this(TAILLE_HISTORIQUE_DEFAUT);
    }

    /**
     * @param tailleHistorique nombre de mesures conservées par service (≥ 1)
     */
    public KnowledgeBase(int tailleHistorique) {
        if (tailleHistorique < 1) {
            throw new IllegalArgumentException("tailleHistorique doit être ≥ 1");
        }
        this.tailleHistorique = tailleHistorique;
    }

    // ───────────────────────────── Mesures ─────────────────────────────

    /**
     * @return copie immuable de la dernière mesure de chaque service (clé = nom du service)
     */
    public Map<String, MesureSante> getMesures() {
        return Map.copyOf(mesures);
    }

    /**
     * @param service nom du service
     * @return la dernière mesure du service, si elle existe
     */
    public Optional<MesureSante> getMesure(String service) {
        return Optional.ofNullable(mesures.get(service));
    }

    /**
     * Enregistre une nouvelle mesure : elle remplace l'état courant du service et est ajoutée
     * à l'historique. Si une mesure plus récente est déjà enregistrée (messages arrivés dans le
     * désordre), l'état courant n'est pas remplacé.
     *
     * @param service nom du service (clé)
     * @param m       mesure reçue
     * @throws NullPointerException si un argument est {@code null}
     */
    public void majMesure(String service, MesureSante m) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(m, "mesure");
        mesures.compute(service, (k, ancienne) ->
                (ancienne == null || m.timestamp >= ancienne.timestamp) ? m : ancienne);
        Deque<MesureSante> h = historique.computeIfAbsent(service, k -> new ArrayDeque<>());
        synchronized (h) {
            h.addLast(m);
            while (h.size() > tailleHistorique) {
                h.removeFirst();
            }
        }
    }

    /**
     * @param service nom du service
     * @return copie de l'historique du service, de la plus ancienne à la plus récente mesure
     */
    public List<MesureSante> getHistorique(String service) {
        Deque<MesureSante> h = historique.get(service);
        if (h == null) {
            return List.of();
        }
        synchronized (h) {
            return List.copyOf(h);
        }
    }

    /** @return noms des services pour lesquels au moins une mesure a été reçue */
    public Set<String> getServices() {
        return Set.copyOf(mesures.keySet());
    }

    /**
     * Supprime l'état courant et l'historique d'un service (ex. instance supplémentaire
     * retirée). Le compteur de pannes et la route sont conservés.
     *
     * @param service nom du service
     */
    public void oublierService(String service) {
        mesures.remove(service);
        historique.remove(service);
    }

    // ───────────────────────────── Pannes ─────────────────────────────

    /**
     * @param service nom du service
     * @return nombre de pannes enregistrées pour ce service (0 par défaut)
     */
    public int getNbPannes(String service) {
        return nbPannes.getOrDefault(service, 0);
    }

    /**
     * Incrémente atomiquement le compteur de pannes du service.
     *
     * @param service nom du service
     * @return la nouvelle valeur du compteur
     */
    public int incrementerPannes(String service) {
        return nbPannes.merge(Objects.requireNonNull(service, "service"), 1, Integer::sum);
    }

    // ───────────────────────────── Routage ─────────────────────────────

    /**
     * Retourne la cible vers laquelle le trafic destiné à {@code service} doit être envoyé.
     *
     * @param service nom du service
     * @return la cible configurée, ou {@code service} lui-même si aucune redirection n'existe
     */
    public String getRoute(String service) {
        return routage.getOrDefault(service, service);
    }

    /**
     * Redirige le trafic d'un service vers une cible (nom d'instance de secours, ex.
     * {@code "CalcService#50061"}, ou adresse {@code "localhost:50061"}). Passer {@code null}
     * ou le nom du service lui-même rétablit la route directe.
     *
     * @param service nom du service
     * @param cible   nouvelle cible, ou {@code null} pour supprimer la redirection
     */
    public void setRoute(String service, String cible) {
        Objects.requireNonNull(service, "service");
        if (cible == null || cible.equals(service)) {
            routage.remove(service);
        } else {
            routage.put(service, cible);
        }
    }

    /** @return copie immuable des redirections actives (services sans redirection absents) */
    public Map<String, String> getRoutes() {
        return Map.copyOf(routage);
    }
}
