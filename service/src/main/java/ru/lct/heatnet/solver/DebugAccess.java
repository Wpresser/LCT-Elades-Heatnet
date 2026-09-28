package ru.lct.heatnet.solver;

import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.Router;

/** Доступ к внутренним целям поиска для отладочных тестов. */
public final class DebugAccess {
    private DebugAccess() {
    }

    public static Router.GoalProvider tieInGoals(InputModel m, int dn) {
        return new TieInGoals(m, dn, new NetworkState(m.gf, new Net("debug")));
    }
}
