package cpe.baldespompiers.model.dto;

public class InjuredPeopleDto {
    private Integer id;
    InjuryDto injuryDto;
    private double lon;
    private double lat;
    private VehicleDto vDto;

    public InjuredPeopleDto() {

    }

    public InjuredPeopleDto(Integer id, InjuryDto injuryDto,double lon,
                            double lat, VehicleDto vDto) {
        this.id = id;
        this.injuryDto = injuryDto;
        this.lat=lat;
        this.lon=lon;
        this.vDto=vDto;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public InjuryDto getInjuryDto() {
        return injuryDto;
    }

    public void setInjuryDto(InjuryDto injuryDto) {
        this.injuryDto = injuryDto;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public VehicleDto getvDto() {
        return vDto;
    }

    public void setvDto(VehicleDto vDto) {
        this.vDto = vDto;
    }
}

