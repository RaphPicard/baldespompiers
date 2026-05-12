package cpe.baldespompiers.api.controller;

import com.project.model.dto.FacilityDto;
import fr.cpe.baldespompiers.service.FacilityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/facilities")
public class FacilityRestCrt {

    private final FacilityService facilityService;

    public FacilityRestCrt(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @GetMapping
    public List<FacilityDto> getOurFacilities() {
        return facilityService.getOurFacilities();
    }

    @GetMapping("/{id}")
    public FacilityDto getFacility(@PathVariable String id) {
        return facilityService.getFacilityById(id);
    }
}

