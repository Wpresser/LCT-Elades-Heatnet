package ru.lct.heatnet.routing;

import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.ExistingPipe;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.rules.DiameterTable;
import ru.lct.heatnet.rules.RestrictionRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Препятствия для новой сети заданного ДУ в метрах.
 * <ul>
 *     <li>Запретные ограничения — буфер «отступ + ½ ширины пары»: ось трассы не должна в него заходить.</li>
 *     <li>Ограничения со спецпроходом и существующая теплосеть — зоны отступа, в которые можно зайти
 *     только в составе допустимого спецпрохода (проверяет {@link SegmentChecker}).</li>
 *     <li>Вершины для графа видимости — выпуклые углы объединения чуть более широких буферов.</li>
 * </ul>
 * JTS строит буфер вписанным многоугольником, поэтому радиус увеличен на 1/cos(π/4q): хорды не заходят
 * внутрь истинной зоны отступа.
 */
public final class ObstacleField {

    static final int QUADRANT_SEGMENTS = 3;
    static final double CHORD_FACTOR = 1.0 / Math.cos(Math.PI / (4 * QUADRANT_SEGMENTS));
    /** Запас к радиусу запрета, м. */
    static final double BLOCK_EPS = 0.02;
    /** Вершины графа лежат на столько дальше границы запрета, м. */
    static final double VERTEX_EPS = 0.05;

    public static final class Block {
        public final Restriction restriction;
        public final Geometry geometry;
        final PreparedGeometry prepared;

        Block(Restriction restriction, Geometry geometry) {
            this.restriction = restriction;
            this.geometry = geometry;
            this.prepared = PreparedGeometryFactory.prepare(geometry);
        }
    }

    /** Объект, который можно пересечь только спецпроходом. */
    public static final class Zone {
        public final RestrictionRule rule;
        public final FeatureId id;
        /** Сам объект: полигон дороги или линия газопровода/кабеля/трубы. */
        public final Geometry core;
        /** Зона отступа вокруг объекта для оси новой трубы. */
        public final Geometry zone;
        /** Требуемое расстояние от оси новой трубы до объекта, м. */
        public final double need;
        /** Для существующей теплосети — сама труба. */
        public final ExistingPipe pipe;
        final PreparedGeometry preparedZone;
        final PreparedGeometry preparedCore;

        Zone(RestrictionRule rule, FeatureId id, Geometry core, double need, ExistingPipe pipe) {
            this.rule = rule;
            this.id = id;
            this.core = core;
            this.need = need;
            this.pipe = pipe;
            this.zone = core.buffer(need * CHORD_FACTOR + BLOCK_EPS, QUADRANT_SEGMENTS);
            this.preparedZone = PreparedGeometryFactory.prepare(zone);
            this.preparedCore = PreparedGeometryFactory.prepare(core);
        }

        public boolean isAreal() {
            return core.getDimension() == 2;
        }
    }

    public final InputModel model;
    public final int dn;
    public final double halfWidth;
    public final GeometryFactory gf;
    final List<Block> blocks = new ArrayList<>();
    final STRtree blockIndex = new STRtree();
    final PreparedGeometry blockUnion;
    final Geometry blockUnionGeometry;
    final List<Zone> zones = new ArrayList<>();
    final STRtree zoneIndex = new STRtree();
    final PreparedGeometry roi;
    /** Вершины графа видимости и их соседи по контуру (для отбора касательных рёбер). */
    final List<Coordinate> vertices = new ArrayList<>();
    final List<Coordinate[]> ringNeighbours = new ArrayList<>();
    final STRtree vertexIndex = new STRtree();

    public ObstacleField(InputModel model, int dn) {
        this.model = model;
        this.dn = dn;
        this.halfWidth = DiameterTable.require(dn).halfWidth();
        this.gf = model.gf;
        List<Geometry> vertexShapes = new ArrayList<>();
        for (Restriction r : model.restrictions) {
            if (r.rule.kind == RestrictionRule.Kind.FORBIDDEN) {
                double radius = (r.rule.clearance(dn) + halfWidth) * CHORD_FACTOR + BLOCK_EPS;
                Block b = new Block(r, r.geometry.buffer(radius, QUADRANT_SEGMENTS));
                blocks.add(b);
                blockIndex.insert(b.geometry.getEnvelopeInternal(), b);
                vertexShapes.add(r.geometry.buffer(radius + VERTEX_EPS, QUADRANT_SEGMENTS));
            } else {
                double need = r.rule.clearance(dn) + halfWidth + r.rule.ownWidth / 2.0;
                addZone(new Zone(r.rule, r.id, r.geometry, need, null), vertexShapes);
            }
        }
        for (ExistingPipe p : model.pipes) {
            double need = RestrictionRule.EXISTING_HEAT_NETWORK.clearance(dn) + halfWidth
                    + DiameterTable.widthForExisting(p.diameter) / 2.0;
            addZone(new Zone(RestrictionRule.EXISTING_HEAT_NETWORK, p.id, p.line, need, p), vertexShapes);
        }
        blockIndex.build();
        zoneIndex.build();
        List<Geometry> blockGeoms = new ArrayList<>();
        blocks.forEach(b -> blockGeoms.add(b.geometry));
        this.blockUnionGeometry = blockGeoms.isEmpty() ? gf.createPolygon() : UnaryUnionOp.union(blockGeoms);
        this.blockUnion = PreparedGeometryFactory.prepare(blockUnionGeometry);
        this.roi = PreparedGeometryFactory.prepare(model.roi);
        extractVertices(vertexShapes.isEmpty() ? gf.createPolygon() : UnaryUnionOp.union(vertexShapes));
    }

    private void addZone(Zone z, List<Geometry> vertexShapes) {
        zones.add(z);
        zoneIndex.insert(z.zone.getEnvelopeInternal(), z);
        vertexShapes.add(z.core.buffer(z.need * CHORD_FACTOR + BLOCK_EPS + VERTEX_EPS, QUADRANT_SEGMENTS));
    }

    /** Выпуклые вершины контуров объединения внутри области поиска. */
    private void extractVertices(Geometry union) {
        for (int i = 0; i < union.getNumGeometries(); i++) {
            Geometry g = union.getGeometryN(i);
            if (!(g instanceof Polygon)) {
                continue;
            }
            Polygon p = (Polygon) g;
            addRing(p.getExteriorRing(), true);
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                addRing(p.getInteriorRingN(h), false);
            }
        }
        vertexIndex.build();
    }

    /** Препятствие слева по ходу: оболочка против часовой стрелки, дырки — по часовой. */
    private void addRing(LinearRing ring, boolean shell) {
        Coordinate[] cs = ring.getCoordinates();
        boolean ccw = Orientation.isCCW(cs);
        int n = cs.length - 1;
        for (int k = 0; k < n; k++) {
            int i = (shell == ccw) ? k : n - 1 - k;
            int step = (shell == ccw) ? 1 : -1;
            Coordinate prev = cs[Math.floorMod(i - step, n)];
            Coordinate cur = cs[i];
            Coordinate next = cs[Math.floorMod(i + step, n)];
            double cross = (cur.x - prev.x) * (next.y - cur.y) - (cur.y - prev.y) * (next.x - cur.x);
            if (cross <= 1e-12) {
                continue; // вогнутая или прямая вершина — кратчайшие пути через неё не идут
            }
            if (!roi.covers(gf.createPoint(cur))) {
                continue;
            }
            vertices.add(cur);
            ringNeighbours.add(new Coordinate[]{prev, next});
            vertexIndex.insert(new Envelope(cur), vertices.size() - 1);
        }
    }

    @SuppressWarnings("unchecked")
    List<Integer> verticesWithin(Coordinate c, double radius) {
        Envelope env = new Envelope(c);
        env.expandBy(radius);
        List<Integer> out = new ArrayList<>();
        for (Object o : vertexIndex.query(env)) {
            int i = (Integer) o;
            if (vertices.get(i).distance(c) <= radius) {
                out.add(i);
            }
        }
        return out;
    }

    public int vertexCount() {
        return vertices.size();
    }

    /** Точка внутри запретной области (без учёта исключений). */
    public boolean blocked(Coordinate c) {
        return blockUnion.intersects(gf.createPoint(c));
    }

    /** Точка в зоне отступа объекта со спецпроходом (дальше по лучу может найтись полное пересечение). */
    public boolean insideZone(Coordinate c) {
        org.locationtech.jts.geom.Point p = gf.createPoint(c);
        for (Object o : zoneIndex.query(new Envelope(c))) {
            if (((Zone) o).preparedZone.intersects(p)) {
                return true;
            }
        }
        return false;
    }

    public boolean insideRoi(Coordinate c) {
        return roi.covers(gf.createPoint(c));
    }

    public LineString segment(Coordinate a, Coordinate b) {
        return gf.createLineString(new Coordinate[]{a, b});
    }
}
