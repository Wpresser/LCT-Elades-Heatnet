package ru.lct.heatnet.model;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.model.InputObjects.ExistingPipe;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.model.InputObjects.Source;
import ru.lct.heatnet.model.InputObjects.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Входные данные расчёта в метрах (EPSG:32637) с пространственными индексами. */
public final class InputModel {

    /** Допуск совпадения узлов с концами линий, м (см. DECISIONS.md). */
    public static final double TOPO_TOL = 0.05;

    public final GeometryFactory gf;
    public final List<Source> sources = new ArrayList<>();
    public final List<ExistingPipe> pipes = new ArrayList<>();
    public final List<ExistingChamber> chambers = new ArrayList<>();
    public final List<Target> targets = new ArrayList<>();
    /** Точки подключения без корректного flow_tph: маршрут не строится, в выгрузке они среди неподключённых с причиной. */
    public final Map<Target, String> invalidTargets = new java.util.LinkedHashMap<>();
    public final List<Restriction> restrictions = new ArrayList<>();
    /** Окна поиска вокруг целей; ограничения вне окон не загружаются. */
    public final List<Envelope> windows = new ArrayList<>();
    /** Полигоны ОКС, содержащие цель (для финального подхода, §2.2). */
    public final Map<Target, List<Restriction>> ownOks = new IdentityHashMap<>();
    public final Diagnostics diagnostics = new Diagnostics();

    public final STRtree pipeIndex = new STRtree();
    public final STRtree chamberIndex = new STRtree();
    public final STRtree restrictionIndex = new STRtree();
    /** Объединение окон поиска: маршрут не должен выходить за область с известными ограничениями. */
    public Geometry roi;

    public InputModel(GeometryFactory gf) {
        this.gf = gf;
    }

    public List<Restriction> ownOksOf(Target t) {
        return ownOks.getOrDefault(t, Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    public List<Restriction> restrictionsNear(Envelope env) {
        return restrictionIndex.query(env);
    }

    @SuppressWarnings("unchecked")
    public List<ExistingPipe> pipesNear(Envelope env) {
        return pipeIndex.query(env);
    }

    @SuppressWarnings("unchecked")
    public List<ExistingChamber> chambersNear(Envelope env) {
        return chamberIndex.query(env);
    }
}
