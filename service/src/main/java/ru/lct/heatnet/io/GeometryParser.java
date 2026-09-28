package ru.lct.heatnet.io;

import com.fasterxml.jackson.databind.JsonNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heatnet.geo.Projection;

/** GeoJSON-геометрия (lon/lat) → JTS в EPSG:32637. */
public final class GeometryParser {

    private final Projection projection;
    private final GeometryFactory gf;

    public GeometryParser(Projection projection, GeometryFactory gf) {
        this.projection = projection;
        this.gf = gf;
    }

    public Geometry parse(JsonNode geom) {
        if (geom == null || geom.isNull()) {
            throw new InputFormatException("нет геометрии");
        }
        String type = geom.path("type").asText("");
        JsonNode c = geom.get("coordinates");
        switch (type) {
            case "Point":
                return gf.createPoint(coord(c));
            case "MultiPoint":
                return gf.createMultiPoint(points(c));
            case "LineString":
                return line(c);
            case "MultiLineString": {
                LineString[] lines = new LineString[size(c)];
                for (int i = 0; i < lines.length; i++) {
                    lines[i] = line(c.get(i));
                }
                return gf.createMultiLineString(lines);
            }
            case "Polygon":
                return polygon(c);
            case "MultiPolygon": {
                Polygon[] polys = new Polygon[size(c)];
                for (int i = 0; i < polys.length; i++) {
                    polys[i] = polygon(c.get(i));
                }
                return gf.createMultiPolygon(polys);
            }
            case "GeometryCollection": {
                JsonNode gs = geom.get("geometries");
                Geometry[] parts = new Geometry[size(gs)];
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = parse(gs.get(i));
                }
                return gf.createGeometryCollection(parts);
            }
            default:
                throw new InputFormatException("неизвестный тип геометрии: " + type);
        }
    }

    /** Исходные lon/lat точки (для объектов, которые выгружаются без пересчёта). */
    public static Coordinate lonLatOfPoint(JsonNode geom) {
        if (geom == null || !"Point".equals(geom.path("type").asText())) {
            throw new InputFormatException("ожидалась геометрия Point");
        }
        JsonNode c = geom.get("coordinates");
        checkPosition(c);
        return new Coordinate(c.get(0).asDouble(), c.get(1).asDouble());
    }

    private Coordinate coord(JsonNode c) {
        checkPosition(c);
        return projection.toMetric(c.get(0).asDouble(), c.get(1).asDouble());
    }

    private static void checkPosition(JsonNode c) {
        if (c == null || !c.isArray() || c.size() < 2 || !c.get(0).isNumber() || !c.get(1).isNumber()) {
            throw new InputFormatException("некорректная позиция координат");
        }
        double lon = c.get(0).asDouble();
        double lat = c.get(1).asDouble();
        if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90) {
            throw new InputFormatException("координаты вне диапазона WGS 84: " + lon + ", " + lat);
        }
    }

    private Coordinate[] coords(JsonNode arr) {
        Coordinate[] out = new Coordinate[size(arr)];
        for (int i = 0; i < out.length; i++) {
            out[i] = coord(arr.get(i));
        }
        return out;
    }

    private Point[] points(JsonNode arr) {
        Point[] out = new Point[size(arr)];
        for (int i = 0; i < out.length; i++) {
            out[i] = gf.createPoint(coord(arr.get(i)));
        }
        return out;
    }

    private LineString line(JsonNode arr) {
        Coordinate[] cs = coords(arr);
        if (cs.length < 2) {
            throw new InputFormatException("в линии меньше двух точек");
        }
        return gf.createLineString(cs);
    }

    private Polygon polygon(JsonNode rings) {
        int n = size(rings);
        if (n == 0) {
            throw new InputFormatException("полигон без колец");
        }
        LinearRing shell = ring(rings.get(0));
        LinearRing[] holes = new LinearRing[n - 1];
        for (int i = 1; i < n; i++) {
            holes[i - 1] = ring(rings.get(i));
        }
        return gf.createPolygon(shell, holes);
    }

    private LinearRing ring(JsonNode arr) {
        Coordinate[] cs = coords(arr);
        if (cs.length < 4 || !cs[0].equals2D(cs[cs.length - 1])) {
            throw new InputFormatException("кольцо полигона не замкнуто или меньше 4 точек");
        }
        return gf.createLinearRing(cs);
    }

    private static int size(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            throw new InputFormatException("ожидался массив координат");
        }
        return arr.size();
    }
}
