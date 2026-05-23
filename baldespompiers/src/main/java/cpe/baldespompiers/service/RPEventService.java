package cpe.baldespompiers.service;

import cpe.baldespompiers.client.FacilityClient;
import cpe.baldespompiers.model.dto.EmergencyEventDto;
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.model.dto.VehicleDto;
import cpe.baldespompiers.model.type.EmergencyType;
import cpe.baldespompiers.tools.GisTools;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
    private final FacilityClient facilityClient;

    private final AtomicReference<List<FacilityDto>> knownFacilities = new AtomicReference<>(null);

    @Value("${simulator.team-uuid}")
    private String teamUuid;

    @Value("${dispatch.min.fuel:10.0}")
    private float minFuel;

    @Value("${dispatch.min.crew:1}")
    private int minCrew;

    @Value("${dispatch.abandon.threshold:4.0}")
    private float abandonThreshold;

    public RPEventService(@Lazy EmergencyManagerService emergencyManagerService,
                          FacilityClient facilityClient) {
        this.emergencyManagerService = emergencyManagerService;
        this.facilityClient = facilityClient;
    }

    private void ensureFacilityList() {
        if (knownFacilities.get() != null) return;
        List<FacilityDto> facilities = facilityClient.getAllFacilities(teamUuid);
        if (facilities != null && !facilities.isEmpty()) knownFacilities.set(facilities);
    }

    private Optional<FacilityDto> facilityOf(VehicleDto v) {
        List<FacilityDto> facilities = knownFacilities.get();
        if (facilities == null || v.getFacilityRefID() == null) return Optional.empty();
        return facilities.stream().filter(f -> f.getId().equals(v.getFacilityRefID())).findFirst();
    }

    // ── Vérifie si le véhicule est efficace sur ce type d'event (supérieur à 0 -> donc même un Fire engine va etre efficace à 20% sur un road event !!!) ───────────────────────────────────────────────
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
        // l'efficacité est le facteur le plus important, mais on pénalise aussi la distance (carburant consommé et temps de trajet) et on valorise un peu le carburant restant (car il peut servir pour d'autres events ensuite)
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
                .filter(v -> v.getFuelQuantity() >= minFuel)
                .filter(v -> facilityOf(v)
                        .map(f -> GisTools.hasFuelToReach(v, event.getLon(), event.getLat(), f.getLon(), f.getLat()))
                        .orElseGet(() -> GisTools.hasFuelToReach(v, event.getLon(), event.getLat())));
    }

    // ── Dispatch principal ────────────────────────────────────────────────────
    public void dispatchEvents(List<EmergencyEventDto> events, List<VehicleDto> vehicles) {
        if (events == null || events.isEmpty()) return;
        ensureFacilityList();

        List<EmergencyEventDto> filtered = events.stream()
                .filter(e -> e.getIntensity() > abandonThreshold
                        || (e.getInjuredPeopleDtoList() != null && !e.getInjuredPeopleDtoList().isEmpty()))
                .filter(e -> !emergencyManagerService.getAssignedEvents()
                        .contains(e.getId()))
                .sorted(Comparator.comparingDouble(EmergencyEventDto::getIntensity)
                        .reversed())
                .toList();

        for (EmergencyEventDto event : filtered) {
            candidates(vehicles, event)
                    .max(Comparator.comparingDouble(v -> eventScore(v, event)))
                    .ifPresent(//OrElse(
                            vehicle -> emergencyManagerService.dispatchEvent(vehicle, event) //,    // pour éviter les logs répétitifs qui polluent mon terminal
                            //() -> log.warn("Event #{} (type={}) — aucun véhicule compatible disponible",
                                    //event.getId(), event.getEventType())
                    );
        }
    }

    private double distance(double lon1, double lat1, double lon2, double lat2) {
        double dx = lon1 - lon2;
        double dy = lat1 - lat2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ── Méthodes miroir de FireService (pour la redirection en route) ──────────

    /** Meilleur event non-assigné compatible avec ce véhicule, pour un départ direct. */
    public Optional<EmergencyEventDto> findNextEventForVehicle(VehicleDto vehicle,
                                                               List<EmergencyEventDto> activeEvents) {
        return activeEvents.stream()
                .filter(e -> e.getIntensity() > abandonThreshold)
                .filter(e -> !emergencyManagerService.getAssignedEvents().contains(e.getId()))
                .filter(e -> isCompatibleWithEvent(vehicle, e))
                .max(Comparator.comparingDouble(e -> eventScore(vehicle, e)));
    }

    /** Transition directe d'un event à un autre (sans caserne). */
    public void claimEvent(Integer vehicleId, Integer oldEventId, Integer newEventId) {
        emergencyManagerService.getAssignedEvents().remove(oldEventId);
        emergencyManagerService.getAssignedEvents().add(newEventId);
        emergencyManagerService.getVehicleStates().put(vehicleId, EmergencyManagerService.VehicleState.MOVING);
        log.info("Véhicule {} : transition event #{} → event #{}", vehicleId, oldEventId, newEventId);
    }

    /** Libère l'assignation d'un event (appel du finally dans moveVehicleToEvent). */
    public void releaseEvent(Integer eventId) {
        emergencyManagerService.getAssignedEvents().remove(eventId);
    }
}