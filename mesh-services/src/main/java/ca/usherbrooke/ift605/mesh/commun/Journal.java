package ca.usherbrooke.ift605.mesh.commun;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configuration commune de la journalisation (java.util.logging) pour tous les processus
 * d'AutonomicMesh.
 *
 * <p>Format d'une ligne : {@code 2026-11-15 18:44:03.512 INFO    [Sonde] message}.
 * Toutes les composantes (services, sondes, Monitor, boucle MAPE-K, ChaosMonkey) utilisent
 * ce format afin que la séquence Monitor → Analyze → Plan → Execute soit lisible dans un
 * seul flux de journaux.</p>
 */
public final class Journal {

    private static final String FORMAT =
            "%1$tF %1$tT.%1$tL %4$-7s [%3$s] %5$s%6$s%n";

    private static volatile boolean configure = false;

    private Journal() {
    }

    /**
     * Applique le format commun à la console. Appel idempotent : peut être fait dans chaque
     * {@code main} sans effet de bord.
     */
    public static synchronized void configurer() {
        if (configure) {
            return;
        }
        System.setProperty("java.util.logging.SimpleFormatter.format", FORMAT);
        LogManager.getLogManager().reset();
        Logger racine = Logger.getLogger("");
        Handler console = new ConsoleHandler();
        console.setFormatter(new SimpleFormatter());
        console.setLevel(Level.ALL);
        racine.addHandler(console);
        racine.setLevel(Level.INFO);
        // gRPC/Netty sont très bavards au niveau FINE : on les limite aux avertissements.
        Logger.getLogger("io.grpc").setLevel(Level.WARNING);
        Logger.getLogger("io.netty").setLevel(Level.WARNING);
        configure = true;
    }

    /**
     * Retourne un logger nommé par un nom court (ex. « Sonde », « Monitor »), ce qui rend
     * les journaux plus lisibles que le nom complet de la classe.
     *
     * @param nom nom court affiché entre crochets
     * @return le logger correspondant
     */
    public static Logger logger(String nom) {
        return Logger.getLogger(nom);
    }
}
