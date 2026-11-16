package ca.usherbrooke.ift605.mesh.sonde;

import ca.usherbrooke.ift605.mesh.commun.Journal;
import ca.usherbrooke.ift605.mesh.commun.ServiceMesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Démarre une sonde (un thread) pour chacun des trois services sur leurs ports par défaut.
 *
 * <p>Utilisable de deux façons :</p>
 * <ul>
 *   <li>en processus séparé : {@code java -cp mesh-services-all.jar
 *       ca.usherbrooke.ift605.mesh.sonde.LanceurSondes [hôteMonitor] [portMonitor]} ;</li>
 *   <li>depuis le gestionnaire autonome : {@code LanceurSondes.demarrerToutes("localhost", 9090)}.</li>
 * </ul>
 */
public final class LanceurSondes {

    private LanceurSondes() {
    }

    /**
     * Démarre une sonde par service, chacune dans son propre thread nommé {@code sonde-<Service>}.
     *
     * @param hoteIpc hôte du Monitor
     * @param portIpc port du Monitor
     * @return les sondes démarrées (pour pouvoir les arrêter avec {@link Sonde#arreter()})
     */
    public static List<Sonde> demarrerToutes(String hoteIpc, int portIpc) {
        List<Sonde> sondes = new ArrayList<>();
        for (ServiceMesh s : ServiceMesh.values()) {
            sondes.add(demarrer(new Sonde(s.nom(), ServiceMesh.HOTE_DEFAUT, s.portParDefaut(), hoteIpc, portIpc)));
        }
        return sondes;
    }

    /**
     * Démarre une sonde dans un nouveau thread (utile pour surveiller une instance ajoutée par
     * mise à l'échelle, ex. {@code new Sonde("CalcService#50061", "localhost", 50061, "localhost", 9090)}).
     *
     * @param sonde sonde à démarrer
     * @return la même sonde
     */
    public static Sonde demarrer(Sonde sonde) {
        Thread t = new Thread(sonde, "sonde-" + sonde.service());
        t.setDaemon(true);
        t.start();
        return sonde;
    }

    /**
     * @param args {@code [hôteMonitor] [portMonitor]} (défaut : localhost 9090)
     * @throws InterruptedException si le thread principal est interrompu
     */
    public static void main(String[] args) throws InterruptedException {
        Journal.configurer();
        String hote = args.length > 0 ? args[0] : ServiceMesh.HOTE_DEFAUT;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : ServiceMesh.PORT_IPC_DEFAUT;
        List<Sonde> sondes = demarrerToutes(hote, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sondes.forEach(Sonde::arreter), "arret-sondes"));
        Thread.currentThread().join(); // les sondes tournent jusqu'à Ctrl+C
    }
}
