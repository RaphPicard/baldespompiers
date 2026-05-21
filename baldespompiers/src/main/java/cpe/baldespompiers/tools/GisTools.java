package cpe.baldespompiers.tools;

import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.GeodeticCalculator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.opengis.geometry.MismatchedDimensionException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import cpe.baldespompiers.model.dto.Coord;
import cpe.baldespompiers.model.dto.VehicleDto;

public class GisTools {
    public static final int SIR_4326=4326;
    public static final int SIR_3857=3857;

    public static Coord transformCoord(Coord source,String targetedProjection) {

        CoordinateReferenceSystem sourceCRS;
        try {
            sourceCRS = CRS.decode("EPSG:"+source.getProjection());
            CoordinateReferenceSystem targetCRS = CRS.decode("EPSG:"+targetedProjection);
            MathTransform transform = CRS.findMathTransform(sourceCRS, targetCRS, false);
            GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), Integer.valueOf(targetedProjection));


            Point point = geometryFactory.createPoint(new Coordinate(source.getLon(), source.getLat()));
            Point targetPoint = (Point) JTS.transform(point, transform);

            Coord result=new Coord(targetPoint.getX(), targetPoint.getY());
            result.setProjection(targetedProjection);
            return result;
        } catch (FactoryException e) {
            e.printStackTrace();
        } catch (MismatchedDimensionException e) {
            e.printStackTrace();
        } catch (TransformException e) {
            e.printStackTrace();
        }
        return null;
    }

//	public static int computeDistance(Coord c1, Coord c2) {
//
//			CoordinateReferenceSystem crs;
//			try {
//				crs = CRS.decode("EPSG:"+SIR_4326);
//
//				Coordinate start=new Coordinate(c1.getLon(), c1.getLat());
//		        Coordinate end=new Coordinate(c2.getLon(), c2.getLat());
//				GeodeticCalculator gc = new GeodeticCalculator(crs);
//
//				gc.setStartingPosition(JTS.toDirectPosition(start, crs));
//				gc.setDestinationPosition(JTS.toDirectPosition(end, crs));
//		        double distance = gc.getOrthodromicDistance();
//		        int totalmeters = (int) distance;
//		        return totalmeters;
//			} catch (NoSuchAuthorityCodeException e) {
//				e.printStackTrace();
//			} catch (FactoryException e) {
//				e.printStackTrace();
//			} catch (TransformException e) {
//				e.printStackTrace();
//			}
//			return -1;
//	}


    /**
     * Retourne true si le véhicule a assez de carburant pour atteindre la mission
     * ET rentrer à sa caserne.
     * Formule : (dist_aller + dist_retour) * fuelConsumption(L/100km) / 100 ≤ fuel restant
     */
    public static boolean hasFuelToReach(VehicleDto vehicle, double targetLon, double targetLat,
                                         double facilityLon, double facilityLat) {
        if (vehicle.getType() == null) return true;
        float consumptionPer100km = vehicle.getType().getFuelConsumption();
        if (consumptionPer100km <= 0) return true;

        double distAllerKm   = computeDistance2(new Coord(vehicle.getLon(), vehicle.getLat()),
                                                new Coord(targetLon, targetLat)) / 1000.0;
        double distRetourKm  = computeDistance2(new Coord(targetLon, targetLat),
                                                new Coord(facilityLon, facilityLat)) / 1000.0;
        double fuelNeeded = (distAllerKm + distRetourKm) * consumptionPer100km / 100.0;
        return vehicle.getFuelQuantity() >= fuelNeeded;
    }

    /**
     * Surcharge aller simple (sans caserne connue) — utilisée en fallback.
     */
    public static boolean hasFuelToReach(VehicleDto vehicle, double targetLon, double targetLat) {
        if (vehicle.getType() == null) return true;
        float consumptionPer100km = vehicle.getType().getFuelConsumption();
        if (consumptionPer100km <= 0) return true;

        double distanceKm = computeDistance2(new Coord(vehicle.getLon(), vehicle.getLat()),
                                             new Coord(targetLon, targetLat)) / 1000.0;
        double fuelNeeded = distanceKm * consumptionPer100km / 100.0;
        return vehicle.getFuelQuantity() >= fuelNeeded;
    }

    public static int computeDistance2(Coord c1, Coord c2) {
        c1.setProjection(""+SIR_4326);
        c2.setProjection(""+SIR_4326);
        Coord c1trans=GisTools.transformCoord(c1, ""+SIR_3857);
        Coord c2trans=GisTools.transformCoord(c2, ""+SIR_3857);
        double distance = Math.sqrt(Math.pow(c2trans.getLon()-c1trans.getLon(),2)+Math.pow(c2trans.getLat()-c1trans.getLat(),2));
        return (int)distance;
    }

}
