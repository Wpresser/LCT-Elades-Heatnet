package ru.lct.heatnet.model;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnet.rules.RestrictionRule;

/**
 * Объекты входного файла. Геометрия — в EPSG:32637 (метры);
 * для точечных объектов дополнительно хранятся исходные lon/lat, чтобы выгружать их без искажений.
 */
public final class InputObjects {

    private InputObjects() {
    }

    public static final class Source {
        public final FeatureId id;
        public final Coordinate lonLat;
        public final Point point;

        public Source(FeatureId id, Coordinate lonLat, Point point) {
            this.id = id;
            this.lonLat = lonLat;
            this.point = point;
        }
    }

    public static final class ExistingPipe {
        public final FeatureId id;
        public final int diameter;
        public final LineString line;
        /** Номер части, если во входе был MultiLineString. */
        public final int part;

        public ExistingPipe(FeatureId id, int diameter, LineString line, int part) {
            this.id = id;
            this.diameter = diameter;
            this.line = line;
            this.part = part;
        }
    }

    public static final class ExistingChamber {
        public final FeatureId id;
        public final Coordinate lonLat;
        public final Point point;
        /** Сколько существующих линейных участков уже примыкает (конец линии = 1, проходящая линия = 2). */
        public int existingAdjacency;

        public ExistingChamber(FeatureId id, Coordinate lonLat, Point point) {
            this.id = id;
            this.lonLat = lonLat;
            this.point = point;
        }
    }

    public static final class Target {
        public final FeatureId id;
        public final double flowTph;
        public final Coordinate lonLat;
        /** Точка для расчёта (у точки на фасаде — сдвинута на 5 см внутрь своего ОКС). */
        public final Point point;
        /** Исходная точка из входа (EPSG:32637): в ней стоит узел цели и заканчивается участок. */
        public final Point origin;

        public Target(FeatureId id, double flowTph, Coordinate lonLat, Point point) {
            this(id, flowTph, lonLat, point, point);
        }

        public Target(FeatureId id, double flowTph, Coordinate lonLat, Point point, Point origin) {
            this.id = id;
            this.flowTph = flowTph;
            this.lonLat = lonLat;
            this.point = point;
            this.origin = origin;
        }
    }

    public static final class Restriction {
        public final FeatureId id;
        /** restriction_type как во входе. */
        public final String type;
        /** Правило таблицы 2; null — тип не поддерживается (необязательный). */
        public final RestrictionRule rule;
        public final Geometry geometry;

        public Restriction(FeatureId id, String type, RestrictionRule rule, Geometry geometry) {
            this.id = id;
            this.type = type;
            this.rule = rule;
            this.geometry = geometry;
        }

        public boolean isAreal() {
            return geometry.getDimension() == 2;
        }
    }
}
