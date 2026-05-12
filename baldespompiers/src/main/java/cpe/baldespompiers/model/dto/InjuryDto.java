package cpe.baldespompiers.model.dto;

import cpe.baldespompiers.model.type.InjuryType;

public class InjuryDto {

    private InjuryType injuryType;
    private float severity;
    private float intensity;

    //All time express in sec.
    private int timeForTraitment;
    private int timeReminingForTraitment;
    private int deliveryTime;
    private int deliveryTimeRemining;

    public InjuryDto() {
    }

    public InjuryDto(InjuryType injuryType, float severity, float intensity, int timeForTraitment, int timeReminingForTraitment, int deliveryTime, int deliveryTimeRemining) {

        this.injuryType=injuryType;
        this.severity = severity;
        this.intensity = intensity;
        this.timeForTraitment = timeForTraitment;
        this.timeReminingForTraitment = timeReminingForTraitment;
        this.deliveryTime = deliveryTime;
        this.deliveryTimeRemining = deliveryTimeRemining;
    }



    public InjuryType getInjuryType() {
        return injuryType;
    }

    public void setInjuryType(InjuryType injuryType) {
        this.injuryType = injuryType;
    }

    public float getSeverity() {
        return severity;
    }

    public void setSeverity(float severity) {
        this.severity = severity;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }

    public int getTimeForTraitment() {
        return timeForTraitment;
    }

    public void setTimeForTraitment(int timeForTraitment) {
        this.timeForTraitment = timeForTraitment;
    }

    public int getTimeReminingForTraitment() {
        return timeReminingForTraitment;
    }

    public void setTimeReminingForTraitment(int timeReminingForTraitment) {
        this.timeReminingForTraitment = timeReminingForTraitment;
    }

    public int getDeliveryTime() {
        return deliveryTime;
    }

    public void setDeliveryTime(int deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    public int getDeliveryTimeRemining() {
        return deliveryTimeRemining;
    }

    public void setDeliveryTimeRemining(int deliveryTimeRemining) {
        this.deliveryTimeRemining = deliveryTimeRemining;
    }
}

