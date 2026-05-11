import cpe.baldespompiers.model.type;
import java.util.HashMap;
import java.util.Map;

public enum VehicleType {
    // https://lemonbin.com/types-of-fire-trucks/
    CAR(
            2,
            2,
            Map.of(EmergencyType.FIRE,1f,EmergencyType.ROAD_ACCIDENT, 1f,EmergencyType.PERSONAL_INJURY, 5f,EmergencyType.MISCELLANEOUS_OPERATION, 5f),
            10,
            0.1F,
            50,
            5,
            150.0F,
            0),
    FIRE_ENGINE(
            4,
            4,
            Map.of(EmergencyType.FIRE,5f,EmergencyType.ROAD_ACCIDENT, 2f,EmergencyType.PERSONAL_INJURY, 1f,EmergencyType.MISCELLANEOUS_OPERATION, 2f),
            50 ,
            0.5F,
            60,
            10,
            110.0F,
            1),
    PUMPER_TRUCK(
            10,
            6,
            Map.of(EmergencyType.FIRE,20f,EmergencyType.ROAD_ACCIDENT, 2f,EmergencyType.PERSONAL_INJURY, 1f,EmergencyType.MISCELLANEOUS_OPERATION, 2f),
            1000,
            1,
            500,
            25,
            70.0F,
            1),
    WATER_TENDERS(
            10,
            3,
            Map.of(EmergencyType.FIRE,20f,EmergencyType.ROAD_ACCIDENT, 0f,EmergencyType.PERSONAL_INJURY, 0f,EmergencyType.MISCELLANEOUS_OPERATION, 0f),
            1000,
            1,
            500,
            25,
            110.0F,
            0),
    TURNTABLE_LADDER_TRUCK(
            15,
            6,
            Map.of(EmergencyType.FIRE,40f,EmergencyType.ROAD_ACCIDENT, 0f,EmergencyType.PERSONAL_INJURY, 0f,EmergencyType.MISCELLANEOUS_OPERATION, 5f),
            1000,
            3,
            500,
            30,
            70.0F,
            1),
    TRUCK(
            20,
            8,
            Map.of(EmergencyType.FIRE,50f,EmergencyType.ROAD_ACCIDENT, 2f,EmergencyType.PERSONAL_INJURY, 2f,EmergencyType.MISCELLANEOUS_OPERATION, 2f),
            2000,
            3,
            500,
            40,
            110.0F,
            1),
    EMERGENCY_AMBULANCE(
            4,
            2,
            Map.of(EmergencyType.FIRE,0f,EmergencyType.ROAD_ACCIDENT, 20f,EmergencyType.PERSONAL_INJURY, 20f,EmergencyType.MISCELLANEOUS_OPERATION, 20f),
            0,
            0,
            60,
            10,
            110.0F,
            3);


    private int spaceUsedInFacility;
    private int vehicleCrewCapacity;
    private Map<EmergencyType,Float> efficiencyMap =new HashMap<>(); // need all crew member to reach full efficiency value between 0 and 10
    private float liquidCapacity; // total quantity of liquid
    private float liquidConsumption; // per second when use
    private float fuelCapacity; // total quantity of fuel
    private float fuelConsumption; // per km
    private float maxSpeed; // Km/Hour
    private int victimTransportCapacity; // nbre of InjuredPeople

    private VehicleType(int spaceUsedInFacility, int vehicleCrewCapacity, Map<EmergencyType,Float> efficiencyMap,
                        float liquidCapacity, float liquidConsumption, float fuelCapacity, float fuelConsumption, float maxSpeed, int victimTransportCapacity) {
        this.spaceUsedInFacility = spaceUsedInFacility;
        this.vehicleCrewCapacity = vehicleCrewCapacity;
        this.liquidCapacity = liquidCapacity;
        this.liquidConsumption = liquidConsumption;
        this.fuelCapacity = fuelCapacity;
        this.fuelConsumption =fuelConsumption;
        this.efficiencyMap = efficiencyMap;
        this.maxSpeed=maxSpeed;
        this.victimTransportCapacity=victimTransportCapacity;
    }

    public int getSpaceUsedInFacility() {
        return this.spaceUsedInFacility;
    }

    public int getVehicleCrewCapacity() {
        return this.vehicleCrewCapacity;
    }

    public Map<EmergencyType, Float> getEfficiencyMap() {
        return efficiencyMap;
    }

    public void setEfficiencyMap(Map<EmergencyType, Float> efficiencyMap) {
        this.efficiencyMap = efficiencyMap;
    }

    public int getVictimTransportCapacity() {
        return victimTransportCapacity;
    }

    public void setVictimTransportCapacity(int victimTransportCapacity) {
        this.victimTransportCapacity = victimTransportCapacity;
    }

    public float getLiquidCapacity() {
        return liquidCapacity;
    }

    public void setLiquidCapacity(float liquidCapacity) {
        this.liquidCapacity = liquidCapacity;
    }

    public float getLiquidConsumption() {
        return liquidConsumption;
    }

    public void setLiquidConsumption(float liquidConsumption) {
        this.liquidConsumption = liquidConsumption;
    }

    public float getFuelCapacity() {
        return fuelCapacity;
    }

    public void setFuelCapacity(float fuelCapacity) {
        this.fuelCapacity = fuelCapacity;
    }

    public float getFuelConsumption() {
        return fuelConsumption;
    }

    public void setFuelConsumption(float fuelConsumption) {
        this.fuelConsumption = fuelConsumption;
    }

    public void setSpaceUsedInFacility(int spaceUsedInFacility) {
        this.spaceUsedInFacility = spaceUsedInFacility;
    }

    public void setVehicleCrewCapacity(int vehicleCrewCapacity) {
        this.vehicleCrewCapacity = vehicleCrewCapacity;
    }

    public float getMaxSpeed() {
        return maxSpeed;
    }

    public void setMaxSpeed(float maxSpeed) {
        this.maxSpeed = maxSpeed;
    }

}
