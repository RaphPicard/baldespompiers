package cpe.baldespompiers.model.dto;

import java.util.ArrayList;
import java.util.List;

public class FireDto {

    private Integer id;
    private String type;
    private float intensity;
    private float range;
    private double lon;
    private double lat;
    private List<InjuredPeopleDto> injuredPeopleDtoList;

    public FireDto() {
        this.injuredPeopleDtoList=new ArrayList<>();
    }

    public FireDto(Integer id, String type, float intensity, float range, double lon, double lat, List<InjuredPeopleDto> injuredPeopleDtoList) {
        super();
        this.id = id;
        this.type = type;
        this.intensity = intensity;
        this.range = range;
        this.lon = lon;
        this.lat = lat;
        if( injuredPeopleDtoList ==null){
            this.injuredPeopleDtoList=new ArrayList<>();
        }else{
            this.injuredPeopleDtoList=injuredPeopleDtoList;
        }

    }
    public Integer getId() {
        return id;
    }
    public void setId(Integer id) {
        this.id = id;
    }
    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
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

    public List<InjuredPeopleDto> getInjuredPeopleDtoList() {
        return injuredPeopleDtoList;
    }

    public void setInjuredPeopleDtoList(List<InjuredPeopleDto> injuredPeopleDtoList) {
        this.injuredPeopleDtoList = injuredPeopleDtoList;
    }
}

