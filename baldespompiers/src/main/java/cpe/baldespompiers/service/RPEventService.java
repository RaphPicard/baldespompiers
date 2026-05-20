package cpe.baldespompiers.service;

import cpe.baldespompiers.model.dto.EmergencyEventDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.model.type.EmergencyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
public class RPEventService {

    private static final Logger log = LoggerFactory.getLogger(RPEventService.class);

    private final EmergencyManagerService emergencyManagerService;

    @Value("${dispatch.min.fuel:10.0}")
    private float minFuel;

    @Value("${dispatch.min.crew:1}")
    private int minCrew;

    @Value("${dispatch.abandon.threshold:4.0}")
    private float abandonThreshold;

    public RPEventService(@Lazy EmergencyManagerService emergencyManagerService) {
        this.emergencyManagerService = emergencyManagerService;
    }

    // ── Vérifie si le véhicule est efficace sur ce type d'event ──────────────
    private boolean isCompatibleWithEvent(VehicleDto v, EmergencyEventDto event) {
        if (v.getType() == null || event.getEventType() == null) return false;
        return v.getType().getEfficiencyMap()
                .getOrDefault(event.getEventType(), 0f) > 0;
    }

    // ── Score d'aptitude pour un event ────────────────────────────────────────
    private double eventScore(VehicleDto v, EmergencyEventDto event) {
        float efficiency = v.getType() != null && event.getEventType() != null
                ? v.getType().getEfficiencyMap().getOrDefault(event.getEventType(), 0f)
                : 0f;
        double dist = distance(v.getLon(), v.getLat(),
                event.getLon(), event.getLat());
        return efficiency * 50.0
                - dist * 300.0
                + v.getFuelQuantity() * 0.1;
    }

    // ── Filtre : véhicules compatibles disponibles ────────────────────────────
    private Stream<VehicleDto> candidates(List<VehicleDto> vehicles,
                                          EmergencyEventDto event) {
        return vehicles.stream()
                .filter(v -> !emergencyManagerService.getVehicleStates()
                        .containsKey(v.getId()))
                .filter(v -> isCompatibleWithEvent(v, event))
                .filter(v -> v.getCrewMember() >= minCrew)
                .filter(v -> v.getFuelQuantity() >= minFuel);
    }

    // ── Dispatch principal ────────────────────────────────────────────────────
    public void dispatchEvents(List<EmergencyEventDto> events, List<VehicleDto> vehicles) {
        if (events == null || events.isEmpty()) return;

        List<EmergencyEventDto> filtered = events.stream()
                .filter(e -> e.getIntensity() > abandonThreshold)
                .filter(e -> !emergencyManagerService.getAssignedEvents()
                        .contains(e.getId()))
                .sorted(Comparator.comparingDouble(EmergencyEventDto::getIntensity)
                        .reversed())
                .toList();

        for (EmergencyEventDto event : filtered) {
            candidates(vehicles, event)
                    .max(Comparator.comparingDouble(v -> eventScore(v, event)))
                    .ifPresentOrElse(
                            vehicle -> emergencyManagerService.dispatchEvent(vehicle, event),
                            () -> log.warn("Event #{} (type={}) — aucun véhicule compatible disponible",
                                    event.getId(), event.getEventType())
                    );
        }
    }

    private double distance(double lon1, double lat1, double lon2, double lat2) {
        double dx = lon1 - lon2;
        double dy = lat1 - lat2;
        return Math.sqrt(dx * dx + dy * dy);
    }
}