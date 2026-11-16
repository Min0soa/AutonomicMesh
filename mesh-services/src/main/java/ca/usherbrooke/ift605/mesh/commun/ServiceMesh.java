package ca.usherbrooke.ift605.mesh.commun;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Catalogue des micro-services du mesh : nom logique, port par défaut et classe principale.
 *
 * <p>C'est la source unique des <b>commandes de lancement</b> convenues dans l'interface
 * commune (section 4 du partage des tâches) : l'Executor (Partie 2) les réutilise pour
 * redémarrer un service ({@code REDEMARRER}) ou démarrer une instance supplémentaire
 * ({@code MISE_A_ECHELLE}) sur un autre port.</p>
 *
 * <pre>{@code
 * // Redémarrer CalcService sur son port habituel
 * Process p = ServiceMesh.CALC.demarrerProcessus(ServiceMesh.CALC.portParDefaut());
 * // Ajouter une seconde instance sur 50061
 * Process p2 = ServiceMesh.CALC.demarrerProcessus(50061);
 * }</pre>
 */
public enum ServiceMesh {

    /** Service de calcul (opérations arithmétiques). */
    CALC("CalcService", 50051, "ca.usherbrooke.ift605.mesh.services.CalcServer"),
    /** Service de stockage clé-valeur. */
    STORAGE("StorageService", 50052, "ca.usherbrooke.ift605.mesh.services.StorageServer"),
    /** Service de notification interne. */
    NOTIF("NotifService", 50053, "ca.usherbrooke.ift605.mesh.services.NotifServer");

    /** Port TCP du canal IPC sondes → Monitor. */
    public static final int PORT_IPC_DEFAUT = 9090;

    /** Hôte par défaut (tout le mesh tourne en local). */
    public static final String HOTE_DEFAUT = "localhost";

    /**
     * Propriété système permettant de forcer le classpath utilisé pour lancer un service
     * (utile si le gestionnaire est lancé depuis un IDE avec un classpath différent).
     */
    public static final String PROP_CLASSPATH = "mesh.classpath";

    private final String nom;
    private final int portParDefaut;
    private final String classePrincipale;

    ServiceMesh(String nom, int portParDefaut, String classePrincipale) {
        this.nom = nom;
        this.portParDefaut = portParDefaut;
        this.classePrincipale = classePrincipale;
    }

    /** @return nom logique du service, ex. {@code "CalcService"} (clé utilisée dans la KnowledgeBase) */
    public String nom() {
        return nom;
    }

    /** @return port gRPC par défaut (50051, 50052 ou 50053) */
    public int portParDefaut() {
        return portParDefaut;
    }

    /** @return nom complet de la classe {@code main} du serveur */
    public String classePrincipale() {
        return classePrincipale;
    }

    /**
     * Retrouve un service à partir de son nom. Accepte le nom logique ({@code CalcService}),
     * le nom court ({@code calc}) ou un nom d'instance suffixé ({@code CalcService#50061}).
     *
     * @param nomOuInstance nom à rechercher (insensible à la casse)
     * @return le service correspondant
     * @throws IllegalArgumentException si aucun service ne correspond
     */
    public static ServiceMesh parNom(String nomOuInstance) {
        if (nomOuInstance == null || nomOuInstance.isBlank()) {
            throw new IllegalArgumentException("Nom de service vide");
        }
        String base = nomOuInstance.split("[#:]", 2)[0].trim().toLowerCase(Locale.ROOT);
        for (ServiceMesh s : values()) {
            String complet = s.nom.toLowerCase(Locale.ROOT);
            String court = complet.replace("service", "");
            if (base.equals(complet) || base.equals(court) || base.equals(s.name().toLowerCase(Locale.ROOT))) {
                return s;
            }
        }
        throw new IllegalArgumentException("Service inconnu : " + nomOuInstance
                + " (attendu : CalcService, StorageService ou NotifService)");
    }

    /**
     * Construit la commande de lancement du service comme processus Java indépendant,
     * équivalente à {@code java -cp <classpath> <ClassePrincipale> <port>}.
     *
     * @param port port gRPC d'écoute de l'instance
     * @return la commande, prête pour {@link ProcessBuilder}
     */
    public List<String> commandeLancement(int port) {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty(PROP_CLASSPATH, System.getProperty("java.class.path"));
        return List.of(java, "-cp", classpath, classePrincipale, Integer.toString(port));
    }

    /**
     * Démarre une instance du service dans un nouveau processus. La sortie du processus est
     * ajoutée au fichier {@code logs/<NomService>-<port>.log} (créé au besoin).
     *
     * @param port port gRPC de l'instance
     * @return le processus démarré (voir {@link Process#pid()} et {@link Process#destroy()})
     * @throws IOException si le processus ne peut pas être créé
     */
    public Process demarrerProcessus(int port) throws IOException {
        Path dossier = Path.of("logs");
        Files.createDirectories(dossier);
        File journal = dossier.resolve(nom + "-" + port + ".log").toFile();
        return new ProcessBuilder(commandeLancement(port))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(journal))
                .start();
    }
}
