package cpe.baldespompiers.service;


/**
 * Logique métier autour des casernes.
 *
 * Responsabilités :
 *   - CRUD casernes (délègue à FacilityClient)
 *   - Maintien du cache d'état local des casernes
 *   - Connaissance des véhicules disponibles par caserne
 */
import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.client.FacilityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FacilityService {

    private final FacilityClient facilityClient;
    @Value("${simulator.team.uuid}")
    private String teamUuid;


    public FacilityService(FacilityClient facilityClient) {
        this.facilityClient = facilityClient;
    }

    public List<FacilityDto> getOurFacilities() {
        return facilityClient.getAllFacilities(this.teamUuid);
    }

    public FacilityDto getFacilityById(String id) {
        return facilityClient.getFacilityById(id);
    }

}
