package cpe.baldespompiers.thread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.client.FireClient;
import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.FireDto;
import cpe.baldespompiers.model.type.VehicleType;
import cpe.baldespompiers.service.EmergencyManagerService;
import cpe.baldespompiers.service.FireService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Déplacement progressif d'un véhicule via @Async.
 * Un thread par véhicule, exécuté dans vehicleMovementExecutor.
 *
 * Modes (movement.mode dans application.properties) :
 *   "teleport" → PUT direct sur destination finale
 *   "straight" → interpolation ligne droite pas à pas
 *   "road"     → waypoints OSRM (géométrie complète) + interpolation entre chaque segment
 */
@Component
public class VehicleMovementThread {

    private static final Logger log = LoggerFactory.getLogger(VehicleMovementThread.class);

    private final VehicleClient vehicleClient;
    private final FireClient fireClient;
    private final FacilityClient facilityClient;
    private final EmergencyManagerService emergencyManagerService;
    private final FireService fireService;

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    @Value("${movement.mode:teleport}")
    private String movementMode;

    /** Taille d'un pas en degrés (~0.0005° ≈ 55 m, ~0.002° ≈ 220 m). */
    @Value("${movement.step.size:0.0005}")
    private double stepSize;

    /** Délai de base entre deux pas (ms) — référence pour un véhicule à 110 km/h. */
    @Value("${movement.step.delay.ms:300}")
    private long stepDelayMs;

    @Value("${movement.fire.check.delay.ms:3000}")
    private long fireCheckDelayMs;

    // Seuils d'abandon de mission (mêmes valeurs que dans EmergencyManagerService)
    @Value("${dispatch.give_up.fuel:10.0}")
    private float giveUpFuel;
    @Value("${dispatch.give_up.liquid:0.0}")
    private float giveUpLiquid;

    @Value("${dispatch.min.fuel:20.0}")
    private float minFuel;

    @Value("${dispatch.min.liquid:20.0}")
    private float minLiquid;

    // Seuils "prêt" : atteints à la caserne avant de libérer le véhicule pour un nouveau dispatch
    @Value("${dispatch.ready.fuel:40.0}")
    private float readyFuel;

    @Value("${dispatch.ready.liquid:40.0}")
    private float readyLiquid;

    @Autowired
    public VehicleMovementThread(VehicleClient vehicleClient,
                                 FireClient fireClient,
                                 FacilityClient facilityClient,
                                 @Lazy EmergencyManagerService emergencyManagerService, // Lazy pour éviter la dépendance circulaire (EmergencyManagerService dépend de VehicleMovementThread)
                                 @Lazy FireService fireService) { // Lazy va faire en sorte que le bean FireService ne soit injecté que lorsqu'il est réellement utilisé, évitant ainsi la boucle de dépendance au démarrage de l'application
        this.vehicleClient = vehicleClient;
        this.fireClient = fireClient;
        this.facilityClient = facilityClient;
        this.emergencyManagerService = emergencyManagerService;
        this.fireService = fireService;
    }

    // ── Signal d'abandon de mission ───────────────────────────────────────────
    // RuntimeException sans stack trace pour ne pas polluer les logs.
    private static final class InsufficientResourcesException extends RuntimeException {
        InsufficientResourcesException(String msg) { super(msg, null, true, false); }
    }
    // Signal de reprise de mission : levée pendant un retour caserne si recallMode passe à OFF
    private static final class ResumeMissionException extends RuntimeException {
        ResumeMissionException(String msg) { super(msg, null, true, false); }
    }

    /** Contexte d'un déplacement : utilisé pour interpréter l'état recallMode. */
    public enum MovePhase {
        TO_FIRE,      // recallMode=true → abandonne, retourne caserne
        TO_FACILITY,  // recallMode=false → abandonne le retour, libère pour redispatch
        MANUAL        // ignore recallMode (déplacement libre)
    }

    // ── Après exctinction d'un feu ? ───────────────────────────────────────────
    private boolean vehicleNeedsRecharge(VehicleDto v) {
        if (v.getFuelQuantity() < minFuel) return true; // carburant trop bas pour une nouvelle mission
        // Pour les véhicules avec réservoir (camions, pas ambulances) : vérifie aussi le liquide extincteur
        return v.getType() != null && v.getType().getLiquidCapacity() > 0 && v.getLiquidQuantity() < minLiquid;
    }

    // ── Point d'entrée principal ───────────────────────────────────────────────
    // le @Async va permettre de lancer ce processus de déplacement dans un thread séparé, sans bloquer le thread principal du simulateur.
    @Async("vehicleMovementExecutor")
    public void moveVehicle(VehicleDto vehicle, FireDto initialFire, String teamUuid, Runnable onDone) {
        FireDto currentFire = initialFire;
        boolean needsRecharge = false;
        try {
            while (true) {
                // Phase 1 : déplacer le véhicule vers le feu (mode téléport, ligne droite ou route selon config)
                movement_type(vehicle, teamUuid, currentFire.getLon(), currentFire.getLat(), MovePhase.TO_FIRE);
                // Met à jour la position locale pour que les prochains calculs de distance partent du bon endroit
                vehicle.setLon(currentFire.getLon());   // fix d'un bug... (à enlever ?)
                vehicle.setLat(currentFire.getLat());

                // Phase 2 : marquer le véhicule comme "sur le feu" puis attendre l'extinction
                // (le simulateur réduit l'intensité du feu automatiquement quand un véhicule est à sa position)
                emergencyManagerService.getVehicleStates()
                        .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
                log.info("Véhicule {} arrivé sur feu #{} — attente extinction", vehicle.getId(), currentFire.getId());
                waitForFireOut(currentFire.getId(), vehicle.getId()); // bloque ici jusqu'à extinction ou ressources épuisées

                // Relit les vraies ressources depuis le simulateur (carburant et liquide ont diminué pendant la mission)
                VehicleDto refreshed = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
                if (refreshed == null) { needsRecharge = true; break; } // si le véhicule a disparu du simulateur (erreur, suppression…) → on considère qu'il doit rentrer à la caserne pour se "recharger" (reset)
                // Met à jour la position locale pour que les prochains calculs de distance partent du bon endroit
                vehicle.setLon(refreshed.getLon());
                vehicle.setLat(refreshed.getLat());

                // Si les ressources sont trop basses, le véhicule doit rentrer à la caserne (break --> finally) se recharger
                if (vehicleNeedsRecharge(refreshed)) { needsRecharge = true; break; } //break sort de la boucle While(true) et va au error/exeption et finally !!!

                // Mode rappel (global ou individuel) : retour immédiat à la caserne, même si ressources OK
                if (emergencyManagerService.isRecallRequested(vehicle.getId())) { needsRecharge = true; break; } //isRecallrequested renvoie le bouléen de recall mis à true par l'appui du boutton "Rappeler" en html

                // Ressources suffisantes : cherche un autre feu à traiter directement, sans passer par la caserne
                List<FireDto> activeFires = fireClient.getAllFires();
                Optional<FireDto> next = fireService.findNextFireForVehicle(
                        refreshed, activeFires != null ? activeFires : List.of());

                if (next.isEmpty()) {
                    // Aucun feu disponible → le véhicule est libéré (onDone le remettra à disposition)
                    log.info("Véhicule {} opérationnel MAIS aucun feu disponible, retour libre", vehicle.getId());
                    break; // pour l'instant on ne le ramène pas à la caserne, il reste où il est
                }

                FireDto nextFire = next.get();
                log.info("Véhicule {} : ressources suffisantes (liquid = {}, fuel = {}), direct sur feu #{} (sans caserne)",
                        vehicle.getId(),vehicle.getLiquidQuantity(), vehicle.getFuelQuantity(), nextFire.getId());
                // Réserve le nouveau feu atomiquement et libère l'ancien pour les autres véhicules
                fireService.claimFire(vehicle.getId(), currentFire.getId(), nextFire.getId());
                currentFire = nextFire; // reboucle sur la phase 1 avec le nouveau feu
            }

        } catch (InsufficientResourcesException e) { // si jamais plus d'essence/liquide OU RAPPEL DES/DU véhicule demandé en cours de mission → on considère que le véhicule doit rentrer à la caserne pour se "recharger" (reset)
            needsRecharge = true;
            log.warn("[Mission] Véhicule {} abandonne la mission (feu #{}) — {}", vehicle.getId(), currentFire.getId(), e.getMessage());

        } catch (InterruptedException | IOException e) {
            needsRecharge = true;
            Thread.currentThread().interrupt();
            log.error("[Move] Interruption/IO véhicule {} : {}", vehicle.getId(), e.getMessage());
        } catch (Exception e) {
            needsRecharge = true;
            log.error("[Move] Erreur inattendue véhicule {} ({}) : {}", vehicle.getId(), e.getClass().getSimpleName(), e.getMessage());

        } finally {
            try {
                fireService.releaseFire(currentFire.getId());
                if (needsRecharge) { // soit rappel demandé des/du véhicule OU insuffisance liquid/essence OU erreur
                    try {
                        returnToFacility(vehicle);
                        waitForRecharge(vehicle.getId());
                    } catch (ResumeMissionException e) {
                        // recallMode désactivé en cours de retour → on libère le véhicule
                        // (le prochain dispatch le récupèrera, qu'il soit ou non à la caserne)
                        log.info("Véhicule {} : retour interrompu pour reprise mission", vehicle.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[Move] Erreur retour/recharge véhicule {} : {}", vehicle.getId(), e.getMessage());
            }
            if (onDone != null) onDone.run();
        }
    }

    /**
     * Déplacement progressif vers une coordonnée arbitraire (sans logique feu/caserne).
     * Wrapper public @Async autour de movement_type, utilisé par /api/vehicles/{id}/move
     * pour respecter la vitesse max du simulateur.
     */
    @Async("vehicleMovementExecutor")
    public void moveTo(VehicleDto vehicle, double lon, double lat) {
        try {
            movement_type(vehicle, teamUuid, lon, lat, MovePhase.MANUAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Déplacement interrompu pour véhicule {}", vehicle.getId());
        } catch (IOException e) {
            log.error("Erreur déplacement véhicule {} : {}", vehicle.getId(), e.getMessage());
        }
    }

    // ── Sélection du mode de déplacement ──────────────────────────────────────

    private void movement_type(VehicleDto vehicle, String teamUuid, double lon, double lat, MovePhase phase)
            throws InterruptedException, IOException {
        long vehicleDelay = computeStepDelay(vehicle.getType());

        if ("straight".equals(movementMode)) {
            moveToPoint(vehicle.getLon(), vehicle.getLat(), lon, lat, vehicle.getId(), vehicleDelay, phase);

        } else if ("road".equals(movementMode)) {
            moveFollower(vehicle, lon, lat, teamUuid, vehicleDelay, phase);

        } else {
            teleport(vehicle.getId(), lon, lat);
        }
    }

    /**
     * Calcule le délai (ms) entre deux pas selon la vitesse max du type de véhicule.
     * Référence : 110 km/h → stepDelayMs.
     *
     *   CAR              (150 km/h) → stepDelayMs × 110/150 ≈ ×0.73  (plus rapide)
     *   FIRE_ENGINE      (110 km/h) → stepDelayMs × 1.00              (référence)
     *   PUMPER_TRUCK     ( 70 km/h) → stepDelayMs × 110/70  ≈ ×1.57  (plus lent)
     */
    private long computeStepDelay(VehicleType type) {
        if (type == null) return stepDelayMs; // type inconnu → délai par défaut
        float speed = type.getMaxSpeed();
        if (speed <= 0) return stepDelayMs; // vitesse invalide → délai par défaut
        // Plus le véhicule est rapide, plus le délai entre deux pas est court (rapport inversement proportionnel)
        return (long) (stepDelayMs * 110.0 / speed);
    }

    // ── Mode route (OSRM) ─────────────────────────────────────────────────────

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Récupère l'itinéraire routier réel via OSRM (overview=full → géométrie complète),
     * puis déplace le véhicule segment par segment avec interpolation fine.
     */
    private void moveFollower(VehicleDto vehicle,
                              double targetLon, double targetLat,
                              String teamUuid, long vehicleDelay, MovePhase phase)
            throws IOException, InterruptedException {

        // Construit l'URL OSRM : "lon_départ,lat_départ;lon_cible,lat_cible" avec géométrie complète
        String url = "https://router.project-osrm.org/route/v1/driving/"
                + vehicle.getLon() + "," + vehicle.getLat()
                + ";"
                + targetLon + "," + targetLat
                + "?geometries=geojson&overview=full";

        System.out.println("[OSRM] Request : " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // OSRM injoignable ou surchargé → on déplace quand même le véhicule en ligne droite
            System.err.println("[OSRM] HTTP Error : " + response.statusCode() + " — fallback ligne droite");
            moveToPoint(vehicle.getLon(), vehicle.getLat(), targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            return;
        }

        JsonNode root = objectMapper.readTree(response.body());

        if (!"Ok".equals(root.path("code").asText())) {
            // OSRM n'a pas trouvé de route (feu hors réseau routier, zone isolée…) → fallback ligne droite
            System.err.println("[OSRM] Invalid response : " + root.path("code").asText() + " — fallback ligne droite");
            moveToPoint(vehicle.getLon(), vehicle.getLat(), targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            return;
        }

        // Navigue dans le JSON OSRM pour extraire les coordonnées de l'itinéraire
        JsonNode coordinates = root
                .path("routes").get(0)
                .path("geometry")
                .path("coordinates");

        if (coordinates == null || !coordinates.isArray()) {
            // Réponse malformée → fallback ligne droite pour ne pas bloquer le véhicule
            System.err.println("[OSRM] No coordinates found — fallback ligne droite");
            moveToPoint(vehicle.getLon(), vehicle.getLat(), targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            return;
        }

        // Convertit chaque nœud JSON [lon, lat] en tableau de doubles exploitable
        List<double[]> waypoints = new ArrayList<>();
        for (JsonNode coord : coordinates) {
            waypoints.add(new double[]{ coord.get(0).asDouble(), coord.get(1).asDouble() });
        }

        System.out.println("[OSRM] " + waypoints.size() + " waypoints reçus pour véhicule " + vehicle.getId());

        // Parcourt chaque segment de route un par un (de waypoint[i-1] à waypoint[i])
        for (int i = 1; i < waypoints.size(); i++) {
            double[] from = waypoints.get(i - 1);
            double[] to   = waypoints.get(i);
            moveToPoint(from[0], from[1], to[0], to[1], vehicle.getId(), vehicleDelay, phase);
        }

        // OSRM s'arrête à la route la plus proche. Si le feu est en dehors du réseau (forêt, champ…),
        // on parcourt le tronçon restant en ligne droite pour atteindre la position exacte du feu.
        if (!waypoints.isEmpty()) {
            double[] last = waypoints.get(waypoints.size() - 1);
            double dLon = targetLon - last[0];
            double dLat = targetLat - last[1];
            // peut etre : enlever le if et mettre dans tous les cas moveToPoint
            if (Math.sqrt(dLon * dLon + dLat * dLat) > stepSize) {
                moveToPoint(last[0], last[1], targetLon, targetLat, vehicle.getId(), vehicleDelay, phase);
            }
        }

        System.out.println("[Move OSRM] Vehicle " + vehicle.getId() + " arrived at destination.");
    }





    // ── Téléportation ─────────────────────────────────────────────────────────

    private void teleport(Integer vehicleId, double lon, double lat) {
        vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(lon, lat));
    }






    // ── Interpolation pas à pas (partagée par straight et road) ───────────────

    /**
     * Déplace le véhicule pas à pas de (fromLon, fromLat) vers (toLon, toLat).
     * Lance InsufficientResourcesException si le carburant passe sous minFuel en cours de route.
     */
    private void moveToPoint(double fromLon, double fromLat,
                             double toLon, double toLat,
                             Integer vehicleId, long vehicleDelay, MovePhase phase) throws InterruptedException {
        double currentLon = fromLon;
        double currentLat = fromLat;

        while (true) {

            // Check du mode rappel (global ou individuel) à chaque pas → abandonne immédiatement
            if (phase == MovePhase.TO_FIRE && emergencyManagerService.isRecallRequested(vehicleId)) // si rappel demandé lance une InsufficientResourcesException qui va être catch dans moveVehicle et qui va faire que le véhicule va abandonner sa mission et retourner à la caserne
                throw new InsufficientResourcesException("rappel actif — abandon trajet vers feu");
            // Retour caserne : interrompu UNIQUEMENT si recallMode global devient OFF (les rappels individuels doivent finir leur trajet retour)
            if (phase == MovePhase.TO_FACILITY && !emergencyManagerService.isRecallMode()
                    && !emergencyManagerService.isRecallRequested(vehicleId))
                throw new ResumeMissionException("rappel terminé — abandon retour caserne");


            // Calcule le vecteur restant à parcourir et sa longueur (en degrés)
            double dLon = toLon - currentLon;
            double dLat = toLat - currentLat;
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            if (dist <= stepSize) {
                // Distance restante inférieure à un pas : on saute directement sur la destination exacte
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                        new Coord(toLon, toLat));
                Thread.sleep(vehicleDelay);
                break;
            }

            // Avance d'exactement stepSize dans la direction de la destination (normalisation du vecteur)
            double ratio = stepSize / dist;
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            // Envoie la nouvelle position au simulateur et récupère l'état mis à jour du véhicule
            VehicleDto updated = vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                    new Coord(currentLon, currentLat));
            Thread.sleep(vehicleDelay); // attend avant le prochain pas (simule la vitesse du véhicule)

            // Coupe la mission si le carburant est trop bas pour continuer à avancer
            if (updated != null && updated.getFuelQuantity() < giveUpFuel)
                throw new InsufficientResourcesException("carburant insuffisant (fuel=" + updated.getFuelQuantity() + ")");
        }
    }








    // ── Attente extinction du feu ─────────────────────────────────────────────

    /**
     * Attend que le feu soit éteint. Lance InsufficientResourcesException si le
     * véhicule manque de liquide avant l'extinction (uniquement pour les véhicules
     * dont le type a une capacité liquide > 0 ==> pour que ca traite pas les ambulances).
     */
    private void waitForFireOut(Integer fireId, Integer vehicleId) throws InterruptedException {
        while (true) {

            // Mode rappel (global ou individuel) : abandonne immédiatement → catch → retour caserne
            if (emergencyManagerService.isRecallRequested(vehicleId)) {
                throw new InsufficientResourcesException("rappel actif");
            }


            FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break; // feu éteint (intensity = 0, atention aux valeurs résiduelles !!!) ou disparu → on sort

            // Vérifie le niveau de liquide uniquement pour les véhicules avec réservoir (pas les ambulances)
            VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (vehicle != null && vehicle.getType() != null
                    && vehicle.getType().getLiquidCapacity() > 0
                    && vehicle.getLiquidQuantity() < giveUpLiquid)
                // Plus assez de liquide pour continuer → abandonne la mission et rentre à la caserne
                throw new InsufficientResourcesException("liquide insuffisant (liquid=" + vehicle.getLiquidQuantity() + ")");

            log.info("[Feu #{}] intensité = {}", fireId, current.getIntensity());
            Thread.sleep(fireCheckDelayMs); // attend quelques secondes avant de revérifier
        }
    }

    // ── Retour à la caserne ───────────────────────────────────────────────────

    private void returnToFacility(VehicleDto vehicle) throws InterruptedException, IOException {
        if (vehicle.getFacilityRefID() == null) return;
        // Récupère la position réelle du véhicule depuis le simulateur
        // (peut différer du DTO local si la mission a été interrompue en cours de route)
        VehicleDto current = vehicleClient.getVehicleById(String.valueOf(vehicle.getId()));
        if (current != null) {
            vehicle.setLon(current.getLon());
            vehicle.setLat(current.getLat());
        }
        FacilityDto facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;
        movement_type(vehicle, teamUuid, facility.getLon(), facility.getLat(), MovePhase.TO_FACILITY);
        // test pour une autre caserne :
        //movement_type(vehicle, teamUuid, 4.877449999999995, 45.772207882103, MovePhase.TO_FACILITY);

    }

    // ── Attente du rechargement à la caserne ──────────────────────────────────

    /**
     * Attend que le véhicule atteigne les seuils readyFuel et readyLiquid
     * (rechargement automatique par le simulateur quand le véhicule est à la caserne).
     * Ne bloque pas si le type de véhicule n'a pas de capacité liquide/fuel (ex. ambulance).
     */
    private void waitForRecharge(Integer vehicleId) throws InterruptedException {
        VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
        if (vehicle == null || vehicle.getType() == null) return;

        // Détermine ce qui manque : carburant, liquide, ou les deux
        boolean needsFuel   = vehicle.getFuelQuantity() < readyFuel;
        boolean needsLiquid = vehicle.getType().getLiquidCapacity() > 0 && vehicle.getLiquidQuantity() < readyLiquid;
        if (!needsFuel && !needsLiquid) return; // déjà à niveau → pas besoin d'attendre

        log.info("Véhicule {} en rechargement à la caserne (fuel={} liquid={})", vehicleId, vehicle.getFuelQuantity(), vehicle.getLiquidQuantity());

        while (true) {
            Thread.sleep(fireCheckDelayMs); // le rechargement est progressif, on recheck régulièrement
            vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
            if (vehicle == null) break;
            boolean fuelOk   = vehicle.getFuelQuantity()  >= readyFuel;
            // Les ambulances (liquidCapacity == 0) sont toujours considérées "ok" côté liquide
            boolean liquidOk = vehicle.getType().getLiquidCapacity() == 0 || vehicle.getLiquidQuantity() >= readyLiquid;
            if (fuelOk && liquidOk) break; // les deux ressources sont au niveau requis → le véhicule est prêt
            log.info("[Recharge #{}] fuel={} liquid={}", vehicleId, vehicle.getFuelQuantity(), vehicle.getLiquidQuantity());
        }
        log.info("Véhicule {} rechargé — prêt pour une nouvelle mission", vehicleId);
    }
}