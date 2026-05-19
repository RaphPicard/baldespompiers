
package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.model.type.LiquidType;
import cpe.baldespompiers.thread.VehicleMovementThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Cerveau de l'Emergency Manager.
 *
 * Reçoit les feux/événements du EventPollerThread,
 * sélectionne les véhicules via la stratégie d'affectation,
 * et lance les déplacements via VehicleMovementThread.
 *
 * Changer @Qualifier("greedyStrategy") → @Qualifier("optimizedStrategy")
 * pour passer au Lot 3.3 sans modifier ce service.
 */
@Service
public class EmergencyManagerService {

    private static final Logger log = LoggerFactory.getLogger(EmergencyManagerService.class);

    public enum VehicleState { MOVING, ON_FIRE }

    private final VehicleMovementThread vehicleMovementThread;
    private final Map<Integer, VehicleState> vehicleStates = new ConcurrentHashMap<>();
    private final Set<Integer> assignedFires = ConcurrentHashMap.newKeySet();

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    // Seuil absolu : en dessous, le véhicule est exclu du dispatch
    @Value("${dispatch.min.fuel:10.0}")
    private float minFuel;

    @Value("${dispatch.min.liquid:10.0}")
    private float minLiquid;

    @Value("${dispatch.min.crew:1}")
    private int minCrew;

    // Seuil "prêt" : on préfère envoyer un véhicule au-dessus de ces valeurs.
    // Si aucun n'est disponible, on accepte tout véhicule > seuil minimum.
    @Value("${dispatch.ready.fuel:40.0}")
    private float readyFuel;

    @Value("${dispatch.ready.liquid:40.0}")
    private float readyLiquid;

    @Value("${dispatch.ready.crew:3}")
    private int readyCrew;

    // Poids/Importante des paramètres dans le calcul du score des véhicules pour dispatch
    @Value("${dispatch.efficiency-weight:50.0}")
    private double efficiencyWeight;

    @Value("${dispatch.crewMember-weight:5.0}")
    private double crewMemberWeight;

    @Value("${dispatch.liquid-weight:20.0}")
    private double liquidWeight;

    @Value("${dispatch.fuel-weight:20.0}")
    private double fuelWeight;

    @Value("${dispatch.distance-weight:300.0}")
    private double distanceWeight;


    public EmergencyManagerService(VehicleMovementThread vehicleMovementThread) {
        this.vehicleMovementThread = vehicleMovementThread;
    }







    // ── Compatibilité liquide ──────────────────────────────────────────────────

    /** Vrai si le liquide du véhicule est efficace contre ce type de feu (efficacité > 10 %). */
    private boolean isLiquidCompatible(LiquidType liquid, String fireType) {
        if (liquid == null || fireType == null) return false; // données manquantes → incompatible par sécurité
        // Un seuil de 10 % évite d'envoyer un véhicule totalement inefficace (ex. eau sur feu chimique)
        return liquid.getEfficiency(fireType) > 0.1;
    }

    // ── Calcul Distance au feu ──────────────────────────────────────────────────
    private static double calcule_distance(double lat1, double lon1, double lat2, double lon2) {
        double dLon = lon1 - lon2;
        double dLat = lat1 - lat2;
        return Math.sqrt(dLon * dLon + dLat * dLat); // en degré... (à changer ?)
    }

    // ── Score d'aptitude ───────────────────────────────────────────────────────

    /**
     * Score d'aptitude d'un véhicule pour un feu donné.
     * Pondération : compatibilité liquide (x50) > équipage (x10) > -distance (x5) > liquide + carburant.
     * Un véhicule parfaitement compatible (efficiency=1.0) gagne +50, ce qui prime sur
     * les petites différences de ressources, mais pas sur la taille de l'équipage.
     */
    private double vehicleScore(VehicleDto v, FireDto fire) {
        // Récupère l'efficacité du liquide contre ce type de feu (entre 0.0 et 1.0), 0 si pas de liquide
        double efficiency = (v.getLiquidType() != null)
                ? v.getLiquidType().getEfficiency(fire.getType())
                : 0.0;
        // Récupère le ratio du liquide embarqué par rapport à la capacité du véhicule
        double liquidRatio = (v.getLiquidType() != null && v.getType().getLiquidCapacity() > 0) // pour prendre en compte la quantité de liquide embarquée par rapport à la capacité du véhicule
                ? v.getLiquidQuantity()  / v.getType().getLiquidCapacity()
                : 0.0;
        // Récupère le ratio du carburant embarqué par rapport à la capacité du véhicule
        double fuelRatio = (v.getType() != null && v.getType().getFuelCapacity() > 0) // pour prendre en compte la quantité d'essence embarquée par rapport à la capacité du véhicule
                ? v.getFuelQuantity()  / v.getType().getFuelCapacity()
                : 0.0;
        // Récupère la distance du véhicule au feu
        double distVehicleFire = calcule_distance(v.getLat(), v.getLon(), fire.getLat(), fire.getLon());

        // Score total = compatibilité liquide (priorité haute) + taille équipage + quantité de ressources restantes
        // Le ×50 sur l'efficacité garantit qu'un véhicule compatible bat toujours un véhicule incompatible bien chargé
        return efficiency * efficiencyWeight             // 0–50  (priorité absolue : liquide compatible ?)
                //+ v.getCrewMember() * crewMemberWeight // INUTILE CAR TOUS LES VEHICULES SERONT PLEINS  // 0–40  (équipage 1–8 pompiers)
                - distVehicleFire * distanceWeight       // 0–20  (carburant suffisant ?)
                + liquidRatio * liquidWeight             // 0–20  (réservoir plein ?)
                + fuelRatio * fuelWeight;                // 0–-45 (0.15° ≈ 16 km max à Lyon)
    }

    // ── Filtres de candidats ───────────────────────────────────────────────────

    /** Véhicules libres, au-dessus des seuils minimaux et compatibles avec le type de feu. */
    private Stream<VehicleDto> candidates(List<VehicleDto> vehicles, FireDto fire) {
        return vehicles.stream()
                .filter(v -> !vehicleStates.containsKey(v.getId())) //vérifier disponibilité (pas déjà en mission)
                .filter(v -> isLiquidCompatible(v.getLiquidType(), fire.getType()))
                .filter(v -> v.getCrewMember() >= minCrew)
                .filter(v -> v.getFuel() >= minFuel)
                .filter(v -> v.getLiquidQuantity() >= 100);
    }

    /** Véhicules "prêts" : candidats valides avec ressources au-dessus des seuils préférés. */
    private Stream<VehicleDto> best_candidates(List<VehicleDto> vehicles, FireDto fire) {
        return candidates(vehicles, fire)
                .filter(v -> !vehicleStates.containsKey(v.getId())) //vérifier disponibilité (pas déjà en mission)
                .filter(v -> isLiquidCompatible(v.getLiquidType(), fire.getType()))
                .filter(v -> v.getFuel() >= readyFuel)
                .filter(v -> v.getLiquidQuantity() >= readyLiquid)
                .filter(v -> v.getCrewMember() >= readyCrew);
    }

    // ── Dispatch ───────────────────────────────────────────────────────────────

    public void dispatchAll(List<FireDto> fires, List<VehicleDto> vehicles) {
        // Trie les feux pour traiter en priorité ceux qu'il est le plus difficile de couvrir
        // (évite qu'un feu "rare" voie son seul véhicule compatible partir sur un autre feu d'abord)
        List<FireDto> sortedFires = fires.stream()
                .sorted(Comparator
                        // 1. Feux avec peu de véhicules compatibles en premier (véhicules "rares" réservés)
                .comparingInt((FireDto f) -> (int) candidates(vehicles, f).count())
                // 2. À égalité de compatibilité, les plus intenses d'abord
                .thenComparingDouble(f -> -f.getIntensity())) // -f pour un tri décroissant
                .toList();

        for (FireDto fire : sortedFires) {
            if (assignedFires.contains(fire.getId())) continue; // un véhicule est déjà en route → on passe

            // Tier 1 : cherche un véhicule avec ressources confortables (fuel ≥ readyFuel, liq ≥ readyLiquid)
            Optional<VehicleDto> ready = best_candidates(vehicles, fire)
                    .max(Comparator.comparingDouble(v -> vehicleScore(v, fire))); // prend le meilleur score

            if (ready.isPresent()) {
                dispatch(ready.get(), fire); // envoie le meilleur véhicule prêt
                continue; // inutile d'examiner le Tier 2 pour ce feu
            }

            // Tier 2 : aucun véhicule "prêt" → envoie le moins mauvais disponible au-dessus des seuils minimaux
            candidates(vehicles, fire)
                    .max(Comparator.comparingDouble(v -> vehicleScore(v, fire)))
                    .ifPresent(
                            vehicle -> {
                                log.warn("Feu #{} — aucun véhicule prêt complètement (fuel≥{}/liq≥{}), dispatch avec ressources partielles : véhicule {} (fuel={}, liq={})",
                                        fire.getId(), readyFuel, readyLiquid,
                                        vehicle.getId(), vehicle.getFuel(), vehicle.getLiquidQuantity());
                                dispatch(vehicle, fire);
                            }

//                        // IDENTIFIER LA RAISON DE PK ON NE PEUT PAS TRAITER CE FEU !!!
//                            () -> {
//                        boolean allBusy = vehicles.stream().allMatch(v -> vehicleStates.containsKey(v.getId())); //.allMatch retourne true su TOUS les véhicules sont occupés (présents dans vehicleStates)
//                        boolean incompatible = vehicles.stream()
//                                .filter(v -> !vehicleStates.containsKey(v.getId()))
//                                .noneMatch(v -> isLiquidCompatible(v.getLiquidType(), fire.getType())); // .noneMatch() retourne TRUE si aucun véhicules n'est compatible (filtré pour ne garder que les véhicules libres)
//                        if (allBusy)
//                            log.warn("Feu #{} (type={}) — tous les véhicules sont occupés", fire.getId(), fire.getType());
//                        else if (incompatible)
//                            log.warn("Feu #{} (type={}) — aucun véhicule avec liquide compatible", fire.getId(), fire.getType());
//                        else
//                            log.warn("Feu #{} (type={}) — tous les véhicules compatibles sont sous le seuil minimum", fire.getId(), fire.getType());
//                    }
                    );
        }
    }

    public void dispatch(VehicleDto vehicle, FireDto fire) {
        log.info("Dispatch véhicule {} → feu #{} (intensité={})", vehicle.getId(), fire.getId(), fire.getIntensity());
        vehicleStates.put(vehicle.getId(), VehicleState.MOVING); // réserve le véhicule : il ne sera plus proposé à d'autres feux
        assignedFires.add(fire.getId());                          // réserve le feu : aucun autre véhicule ne sera dispatchés dessus
        // Lance le déplacement dans un thread dédié (@Async) ; onArrived sera appelé à la fin de la mission
        vehicleMovementThread.moveVehicle(
                vehicle,
                fire,
                teamUuid,
                () -> onArrived(vehicle.getId(), fire.getId())
        );
    }

    // Appelée à la fin de chaque mission (après retour caserne + recharge), libère le véhicule pour un nouveau dispatch
    public void onArrived(Integer vehicleId, Integer fireId) {
        log.info("Véhicule {} libéré", vehicleId);
        vehicleStates.remove(vehicleId); // le véhicule est à nouveau disponible
        assignedFires.remove(fireId);    // le feu est retiré des assignations (éteint ou abandonné)
    }

    /**
     * Cherche le meilleur feu non-assigné compatible avec ce véhicule pour un départ direct
     * (sans repassage par la caserne). Réutilise isLiquidCompatible et vehicleScore.
     */
    public Optional<FireDto> findNextFireForVehicle(VehicleDto vehicle, List<FireDto> activeFires) {
        return activeFires.stream()
                .filter(f -> f.getIntensity() > 0)                                   // ignore les feux déjà éteints
                .filter(f -> !assignedFires.contains(f.getId()))                     // ignore les feux déjà pris
                .filter(f -> isLiquidCompatible(vehicle.getLiquidType(), f.getType())) // liquide efficace requis
                .max(Comparator.comparingDouble(f -> vehicleScore(vehicle, f)));      // prend le feu pour lequel ce véhicule est le plus efficace
    }

    /**
     * Transition directe d'un feu à un autre (sans caserne) :
     * libère l'ancien feu dans assignedFires, prend le nouveau, repasse en MOVING.
     */
    public void claimFire(Integer vehicleId, Integer oldFireId, Integer newFireId) {
        assignedFires.remove(oldFireId); // libère l'ancien feu immédiatement (un autre véhicule pourra le récupérer)
        assignedFires.add(newFireId);    // réserve le nouveau avant même de partir (évite un double dispatch)
        vehicleStates.put(vehicleId, VehicleState.MOVING); // repasse en déplacement (il n'est plus ON_FIRE)
        log.info("Véhicule {} : transition feu #{} → feu #{} (sans caserne)", vehicleId, oldFireId, newFireId);
    }

    /** Libère uniquement l'assignation du feu (appel du finally dans moveVehicle), sans changer l'état du véhicule. */
    public void releaseFire(Integer fireId) {
        assignedFires.remove(fireId);
    }

    public Map<Integer, VehicleState> getVehicleStates() {
        return vehicleStates;
    }
}
