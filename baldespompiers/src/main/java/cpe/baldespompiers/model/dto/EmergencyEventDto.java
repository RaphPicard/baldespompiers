package cpe.baldespompiers.model.dto;

import com.project.model.type.EmergencyType;
import org.locationtech.jts.geom.Point;
import java.util.ArrayList;
import java.util.List;


public class EmergencyEventDto {

    private Integer id;
    private EmergencyType eventType;
    private float intensity;
    private float range;
    private double lon;
    private double lat;
    private List<InjuredPeopleDto> injuredPeopleDtoList;

    public EmergencyEventDto() {
        injuredPeopleDtoList= new ArrayList<>();
    }

    public EmergencyEventDto(Integer id, EmergencyType type, float intensity, float range, double lon, double lat) {
        this.id = id;
        this.eventType = type;
        this.intensity = intensity;
        this.range = range;
        this.lat=lat;
        this.lon=lon;

    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public EmergencyType getEventType() {
        return eventType;
    }

    public void setEventType(EmergencyType eventType) {
        this.eventType = eventType;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }

    public float getRange() {
        return range;
    }

    public void setRange(float range) {
        this.range = range;
    }

    public List<InjuredPeopleDto> getInjuredPeopleDtoList() {
        return injuredPeopleDtoList;
    }

    public void setInjuredPeopleDtoList(List<InjuredPeopleDto> injuredPeopleDtoList) {
        this.injuredPeopleDtoList = injuredPeopleDtoList;
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
}

