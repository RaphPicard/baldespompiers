package cpe.baldespompiers.api.controller;

import cpe.baldespompiers.model.dto.FacilityDto;
import cpe.baldespompiers.service.FacilityService;
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
    public FacilityDto getFacilityById(@PathVariable String id) {
        return facilityService.getFacilityById(id);
    }
}

