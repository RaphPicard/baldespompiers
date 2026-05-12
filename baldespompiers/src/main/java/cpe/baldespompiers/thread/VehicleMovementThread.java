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
import cpe.baldespompiers.service.EmergencyManagerService;
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


/**
 * Déplacement progressif d'un véhicule via @Async.
 * Un thread par véhicule, exécuté dans vehicleMovementExecutor.

 * Modes (movement.mode dans application.properties) :
 *   "teleport" → PUT direct sur destination finale
 *   "straight" → interpolation ligne droite (+50 pts, ×1)
 *   "road"     → waypoints OSRM               (+50 pts, ×2 ou ×3 avec vitesse)
 */

@Component
public class VehicleMovementThread {

    private static final Logger log = LoggerFactory.getLogger(VehicleMovementThread.class);

    private final VehicleClient vehicleClient;
    private final FireClient fireClient;
    private final FacilityClient facilityClient;
    private final EmergencyManagerService emergencyManagerService;

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    @Value("${movement.mode:teleport}")
    private String movementMode;

    @Value("${movement.step.size:0.002}")
    private double stepSize;

    @Value("${movement.step.delay.ms:500}")
    private long stepDelayMs;

    @Value("${movement.fire.check.delay.ms:3000}")
    private long fireCheckDelayMs;

    @Autowired
    public VehicleMovementThread(VehicleClient vehicleClient,
                                 FireClient fireClient,
                                 FacilityClient facilityClient,
                                 @Lazy EmergencyManagerService emergencyManagerService) {
        this.vehicleClient = vehicleClient;
        this.fireClient = fireClient;
        this.facilityClient = facilityClient;
        this.emergencyManagerService = emergencyManagerService;
    }

    @Async("vehicleMovementExecutor") // voir dans config/AppConfig.java
    public void moveVehicle(VehicleDto vehicle, FireDto fire, String teamUuid, Runnable onDone) {
        try {

            // Phase 1 : aller au feu
            movement_type(vehicle, teamUuid, fire.getLon(), fire.getLat());

            // Phase 2 : on est sur le feu, on attend qu'il soit éteint
            emergencyManagerService.getVehicleStates()
                    .put(vehicle.getId(), EmergencyManagerService.VehicleState.ON_FIRE);
            log.info("Véhicule {} arrivé sur feu #{} — attente extinction", vehicle.getId(), fire.getId());

            waitForFireOut(fire.getId());

            // Phase 3 : retour à la caserne
            returnToFacility(vehicle);

        } catch (InterruptedException | IOException e) {
            Thread.currentThread().interrupt();
            System.err.println("[Move] Erreur OSRM pour véhicule " + vehicle.getId() + " : " + e.getMessage());
        } finally {
            // Phase 4 : libération dans tous les cas
            if (onDone != null) onDone.run();
        }
    }

    /**
     * Aiguilleur de déplacement : choisit la stratégie en fonction de la valeur
     * de la propriété {@code movement.mode} dans application.properties.
     *
     * Trois modes disponibles :
     *   "straight" = déplacement simulé en ligne droite, pas à pas
     *   "road"     = déplacement en suivant les vraies routes (via API OSRM)
     *   autre      = téléportation instantanée à destination (mode par défaut)
     *
     * @param vehicle  le véhicule à déplacer (contient sa position courante)
     * @param teamUuid identifiant de l'équipe, requis par l'API simulateur
     * @param lon      longitude de la destination
     * @param lat      latitude de la destination
     */
    private void movement_type(VehicleDto vehicle, String teamUuid, double lon, double lat) throws InterruptedException, IOException {
        if ("straight".equals(movementMode)) {
            // Mode ligne droite : interpolation géométrique entre position actuelle et destination
            moveStraightLine(
                    vehicle.getLon(),   // longitude de départ
                    vehicle.getLat(),   // latitude de départ
                    lon,                // longitude d'arrivée
                    lat,                // latitude d'arrivée
                    vehicle.getId()
            );

        } else if ("road".equals(movementMode)) {
            // Mode route réelle : récupération du tracé via l'API OSRM puis suivi waypoint par waypoint
            moveFollower(
                    vehicle,
                    lon,
                    lat,
                    teamUuid
            );

        } else {
            // Mode téléportation (défaut) : un seul appel API, le véhicule apparaît directement à destination
            teleport(vehicle.getId(),
                    lon,
                    lat);
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();



    /**
     * Déplace le véhicule en suivant le tracé routier réel fourni par l'API OSRM.
     *
     * OSRM (Open Source Routing Machine) est un moteur de calcul d'itinéraires
     * basé sur OpenStreetMap. On lui envoie un point de départ et un point d'arrivée,
     * et il renvoie une liste de coordonnées GPS formant le chemin à suivre sur la route.
     * Le véhicule est ensuite déplacé un waypoint à la fois, avec une pause entre chaque.
     *
     * @param vehicle   le véhicule à déplacer (fournit sa position de départ)
     * @param targetLon longitude de destination
     * @param targetLat latitude de destination
     * @param teamUuid  identifiant de l'équipe, requis par l'API simulateur
     */
    private void moveFollower(VehicleDto vehicle,
                              double targetLon,
                              double targetLat,
                              String teamUuid)
            throws IOException, InterruptedException {

        // L'API OSRM attend les coordonnées au format "lon,lat;lon,lat"
        // "geometries=geojson" → réponse en format GeoJSON standard
        // "overview=simplified" → tracé simplifié (moins de waypoints, suffisant pour la simulation)
        String url_ending = "?geometries=geojson&overview=simplified";
        String url_beginning = "https://router.project-osrm.org/route/v1/driving/";

        // Construction de l'URL : départ "lon,lat" + ";" + arrivée "lon,lat"
        String url = url_beginning
                + vehicle.getLon() + "," + vehicle.getLat()
                + ";"
                + targetLon + "," + targetLat
                + url_ending;

        System.out.println("[OSRM] Request : " + url);

        // Création de la requête HTTP GET vers le serveur OSRM public
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(20))  // abandon si pas de réponse en 20 s
                .build();

        // Envoi de la requête et lecture de la réponse en texte brut (JSON)
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Si le serveur OSRM ne répond pas correctement, on abandonne le déplacement
        if (response.statusCode() != 200) {
            System.err.println("[OSRM] HTTP Error : " + response.statusCode());
            return;
        }

        // Conversion du texte JSON en arbre de nœuds navigable (Jackson ObjectMapper)
        JsonNode root = objectMapper.readTree(response.body());

        // OSRM renvoie un champ "code" : "Ok" si le calcul a réussi, autre valeur sinon
        if (!"Ok".equals(root.path("code").asText())) {
            System.err.println("[OSRM] Invalid response : "
                    + root.path("code").asText());
            return;
        }

        // Navigation dans la structure JSON OSRM :
        // root → routes[0] → geometry → coordinates  (tableau de [lon, lat])
        JsonNode coordinates = root
                .path("routes")
                .get(0)           // on prend le premier itinéraire proposé (le meilleur)
                .path("geometry")
                .path("coordinates");

        // Sécurité : si OSRM n'a pas renvoyé de tableau de coordonnées, on s'arrête
        if (coordinates == null || !coordinates.isArray()) {
            System.err.println("[OSRM] No coordinates found.");
            return;
        }

        // Parcours de chaque waypoint du tracé et déplacement du véhicule
        for (JsonNode coord : coordinates) {
            double lon = coord.get(0).asDouble();  // OSRM encode [longitude, latitude]
            double lat = coord.get(1).asDouble();

            // Envoi de la nouvelle position au simulateur via l'API REST
            vehicleClient.moveVehicle(
                    teamUuid,
                    String.valueOf(vehicle.getId()),
                    new cpe.baldespompiers.model.dto.Coord(lon, lat)
            );

            // Debug console
            System.out.println("[Move OSRM] Vehicle "
                    + vehicle.getId()
                    + " -> "
                    + lon + ", "
                    + lat);

            // Pause pour que l'animation soit visible dans le simulateur
            Thread.sleep(stepDelayMs);
        }

        System.out.println("[Move OSRM] Vehicle "
                + vehicle.getId()
                + " arrived at destination.");
    }

    // ── Téléportation ─────────────────────────────────────────────────────────
    /**
     * Déplace instantanément le véhicule à la destination, sans étape intermédiaire.
     *
     * Un seul appel à l'API simulateur suffit : le véhicule "saute" directement
     * à la position cible.
     *
     * @param vehicleId identifiant du véhicule à déplacer
     * @param lon       longitude de la destination finale
     * @param lat       latitude de la destination finale
     */
    private void teleport(Integer vehicleId, double lon, double lat) {
        // Un seul PUT suffit : pas de boucle, pas d'attente, le véhicule est immédiatement à destination
        vehicleClient.moveVehicle(
                teamUuid,
                String.valueOf(vehicleId),
                new cpe.baldespompiers.model.dto.Coord(lon, lat)
        );
    }

    // ── Ligne droite simple ───────────────────────────────────────────────────
    /**
     * Déplace le véhicule en ligne droite de son point de départ à sa destination,
     * pas à pas, sans tenir compte des routes réelles.
     *
     * À chaque itération, on calcule la distance restante jusqu'à la cible.
     * Si elle est inférieure à {@code stepSize}, on place directement le véhicule
     * à l'arrivée. Sinon, on avance d'exactement {@code stepSize} unités dans la
     * direction de la cible (interpolation vectorielle), puis on attend {@code stepDelayMs}
     * millisecondes avant le prochain pas. ==> Animation sur la map !!!
     *
     * @param startLon  longitude du point de départ
     * @param startLat  latitude du point de départ
     * @param targetLon longitude de la destination
     * @param targetLat latitude de la destination
     * @param vehicleId identifiant du véhicule à déplacer
     */
    private void moveStraightLine(double startLon, double startLat,
                                  double targetLon, double targetLat,
                                  Integer vehicleId) throws InterruptedException {
        double currentLon = startLon;
        double currentLat = startLat;

        while (true) {
            // Vecteur restant entre la position actuelle et la destination
            double dLon = targetLon - currentLon;
            double dLat = targetLat - currentLat;

            // Distance euclidienne restante (en degrés de coordonnées, pas en mètres)
            double dist = Math.sqrt(dLon * dLon + dLat * dLat);

            // Si on est à moins d'un pas de la cible, on se pose directement dessus pour éviter un dépassement
            if (dist <= stepSize) {
                vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId),
                        new cpe.baldespompiers.model.dto.Coord(targetLon, targetLat));
                break;
            }

            // ratio = fraction du vecteur à parcourir pour avancer exactement de stepSize
            // ex : si dist = 0.10 et stepSize = 0.02, ratio = 0.2 → on avance de 20 % du vecteur
            double ratio = stepSize / dist;

            // Application du déplacement : nouvelle position = ancienne + fraction du vecteur
            currentLon += dLon * ratio;
            currentLat += dLat * ratio;

            // Envoi de la nouvelle position au simulateur
            vehicleClient.moveVehicle(teamUuid, String.valueOf(vehicleId), new Coord(currentLon, currentLat));

            // Pause pour que l'animation soit visible dans le simulateur
            Thread.sleep(stepDelayMs);
        }
    }

    // ── Attendre que le feu soit éteint ───────────────────────────────────────
    private void waitForFireOut(Integer fireId) throws InterruptedException {
        while (true) {
            cpe.baldespompiers.model.dto.FireDto current = fireClient.getFireById(fireId);
            if (current == null || current.getIntensity() <= 0) break;
            log.info("[Feu #{}] intensité = {}", fireId, current.getIntensity());
            Thread.sleep(fireCheckDelayMs);
        }
    }


    // ── Retour caserne ────────────────────────────────────────────────────────
    private void returnToFacility(VehicleDto vehicle) throws InterruptedException, IOException {
        if (vehicle.getFacilityRefID() == null) return;

        FacilityDto facility = facilityClient.getFacilityById(String.valueOf(vehicle.getFacilityRefID()));
        if (facility == null) return;

        movement_type(vehicle, teamUuid, facility.getLon(), facility.getLat());
    }
}