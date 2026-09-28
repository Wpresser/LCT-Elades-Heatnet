package ru.lct.heatnet.solver;

import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.ObstacleField;
import ru.lct.heatnet.routing.Router;

import java.util.HashMap;
import java.util.Map;

/** Поля препятствий и маршрутизаторы по ДУ (с кэшем видимости), общие для вариантов одного расчёта. */
public final class RouterPool {

    private final InputModel model;
    private final Map<Integer, Router> routers = new HashMap<>();

    public RouterPool(InputModel model) {
        this.model = model;
    }

    public Router forDn(int dn) {
        return routers.computeIfAbsent(dn, d -> new Router(new ObstacleField(model, d)));
    }

    public InputModel model() {
        return model;
    }

    public int size() {
        return routers.size();
    }
}
