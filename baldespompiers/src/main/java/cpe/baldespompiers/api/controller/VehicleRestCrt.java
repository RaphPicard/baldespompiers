package cpe.baldespompiers.api.controller;

import com.project.model.dto.Coord;
import com.project.model.dto.VehicleDto;
import fr.cpe.baldespompiers.service.VehicleService;
import fr.cpe.baldespompiers.client.VehicleClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;
    private final VehicleClient vehicleClient;
    @Value("${simulator.team-uuid:}");
    private String team_uuid;

    public VehicleController(VehicleService vehicleService,
                             VehicleClient vehicleClient) {
        this.vehicleService = vehicleService;
        this.vehicleClient = vehicleClient;
    }

    @GetMapping
    public List<VehicleDto> getOurVehicles() {
        return vehicleService.getVehiclesForOurTeam();
    }

    @PostMapping
    public VehicleDto addVehicle(@RequestBody VehicleDto dto) {
        return vehicleService.addVehicle(dto);
    }

    @PutMapping("/{vehicleId}")
    public VehicleDto updateVehicle(@PathVariable String vehicleId, @RequestBody VehicleDto dto) {
        return vehicleClient.updateVehicle(this.team_uuid, vehicleId, dto);
    }

    /**
     * Déplacement manuel depuis le front (ex: drag & drop sur la carte).
     */
    @PutMapping("/{vehicleId}/move")
    public VehicleDto moveVehicle(@PathVariable String vehicleId, @RequestBody Coord destination) {
        return vehicleClient.moveVehicle(this.team_uuid, vehicleId, destination);
    }

    /**
     * Suppression sécurisée — refusée si le véhicule est en mission (pénalité -500 pts !!!!)
     */
    @DeleteMapping("/{vehicleId}")
    public Boolean deleteVehicle(@PathVariable String vehicleId) {
        return vehicleService.deleteVehicle(vehicleId);
    }
}
