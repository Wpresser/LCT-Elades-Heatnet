package ru.lct.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/**
 * EPSG:4326 (lon, lat) ↔ EPSG:32637 (WGS 84 / UTM zone 37N), как требует техприложение §1.
 * Экземпляр не потокобезопасен: по одному на расчёт.
 */
public final class Projection {

    public static final String UTM37N = "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs";
    public static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";

    private final CoordinateTransform toMetric;
    private final CoordinateTransform toLonLat;
    private final ProjCoordinate src = new ProjCoordinate();
    private final ProjCoordinate dst = new ProjCoordinate();

    public Projection() {
        CRSFactory crs = new CRSFactory();
        CoordinateReferenceSystem wgs = crs.createFromParameters("EPSG:4326", WGS84);
        CoordinateReferenceSystem utm = crs.createFromParameters("EPSG:32637", UTM37N);
        CoordinateTransformFactory f = new CoordinateTransformFactory();
        this.toMetric = f.createTransform(wgs, utm);
        this.toLonLat = f.createTransform(utm, wgs);
    }

    public Coordinate toMetric(double lon, double lat) {
        src.x = lon;
        src.y = lat;
        toMetric.transform(src, dst);
        return new Coordinate(dst.x, dst.y);
    }

    public Coordinate toLonLat(double x, double y) {
        src.x = x;
        src.y = y;
        toLonLat.transform(src, dst);
        return new Coordinate(dst.x, dst.y);
    }
}
