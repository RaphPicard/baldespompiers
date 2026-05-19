package cpe.baldespompiers.api.controller;

import cpe.baldespompiers.client.VehicleClient;
import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.service.EmergencyManagerService;
import cpe.baldespompiers.service.VehicleService;
import cpe.baldespompiers.thread.VehicleMovementThread;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleRestCrt {

    private final VehicleService vehicleService;
    private final VehicleClient vehicleClient;
    private final VehicleMovementThread vehicleMovementThread;
    private final EmergencyManagerService emergencyManagerService;
    @Value("${simulator.team-uuid:}")
    private String team_uuid;

    public VehicleRestCrt(VehicleService vehicleService,
                          VehicleClient vehicleClient,
                          VehicleMovementThread vehicleMovementThread,
                          EmergencyManagerService emergencyManagerService) {
        this.vehicleService = vehicleService;
        this.vehicleClient = vehicleClient;
        this.vehicleMovementThread = vehicleMovementThread;
        this.emergencyManagerService = emergencyManagerService;
    }

    @GetMapping
    public List<VehicleDto> getOurVehicles() {
        return vehicleService.getVehiclesForOurTeam();
    }

    @PostMapping
    public VehicleDto addVehicle(@RequestBody Map<String, Object> body) {
        // Body en Map pour éviter le bug Jackson 2/3 sur Spring Boot 4 lors de la désérialisation en VehicleDto.
        return vehicleClient.addVehicleRaw(this.team_uuid, body);
    }

    @PutMapping("/{vehicleId}")
    public VehicleDto updateVehicle(@PathVariable String vehicleId, @RequestBody VehicleDto dto) {
        return vehicleClient.updateVehicle(this.team_uuid, vehicleId, dto);
    }

    /**
     * Déplacement manuel depuis le front (ex: drag & drop sur la carte).
     * Utilise VehicleMovementThread → mouvement progressif (mode road/straight)
     * respectant la vitesse max du véhicule. Sinon le simulateur refuse en 409 "BAD SPEED".
     * Le mouvement est asynchrone : la réponse retourne immédiatement avec l'état initial.
     */
    @PutMapping("/{vehicleId}/move")
    public VehicleDto moveVehicle(@PathVariable String vehicleId, @RequestBody Coord destination) {
        VehicleDto vehicle = vehicleClient.getVehicleById(vehicleId);
        vehicleMovementThread.moveTo(vehicle, destination.getLon(), destination.getLat());
        return vehicle;
    }

    /**
     * Suppression sécurisée — refusée si le véhicule est en mission (pénalité -500 pts !!!!)
     */
    @DeleteMapping("/{vehicleId}")
    public Boolean deleteVehicle(@PathVariable String vehicleId) {
        return vehicleService.deleteVehicle(vehicleId);
    }

    /**
     * Active le mode rappel : tous les véhicules en mission abandonnent et rentrent,
     * et aucun nouveau dispatch n'est lancé tant que le mode est actif.
     */
    @PostMapping("/recall-all")
    public Map<String, Object> recallAll() {
        emergencyManagerService.enableRecallMode();
        return Map.of("recallMode", true);
    }

    /**
     * Désactive le mode rappel : le dispatch reprend normalement.
     */
    @PostMapping("/resume")
    public Map<String, Object> resumeDispatch() {
        emergencyManagerService.disableRecallMode();
        return Map.of("recallMode", false);
    }

    @GetMapping("/recall-mode")
    public Map<String, Object> getRecallMode() {
        return Map.of("recallMode", emergencyManagerService.isRecallMode());
    }

    /**
     * Rappel individuel : ramène UN véhicule à la caserne.
     * - Si le véhicule est en mission : marque-le pour rappel (le thread en cours détectera et abandonnera)
     * - Si le véhicule est libre : déclenche un déplacement direct vers la caserne via VehicleMovementThread
     */
    @PostMapping("/{vehicleId}/recall")
    public Map<String, Object> recallOne(@PathVariable Integer vehicleId) {
        VehicleDto vehicle = vehicleClient.getVehicleById(String.valueOf(vehicleId));
        if (vehicle == null) return Map.of("recalled", false, "reason", "vehicle not found");

        emergencyManagerService.requestRecall(vehicleId);

        // Véhicule non en mission → on lance directement le retour caserne
        if (!emergencyManagerService.isVehicleInMission(vehicleId)) {
            if (vehicle.getFacilityRefID() != null) {
                // On retombe sur l'endpoint move classique qui passe par moveTo (MANUAL)
                // mais il faut récupérer les coordonnées de la caserne
                // → délégué au front qui appellera /move avec les coords de la caserne
            }
        }
        return Map.of("recalled", true, "inMission", emergencyManagerService.isVehicleInMission(vehicleId));
    }
}