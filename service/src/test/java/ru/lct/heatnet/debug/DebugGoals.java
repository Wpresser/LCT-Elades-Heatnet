package ru.lct.heatnet.debug;

import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.routing.Router;

public final class DebugGoals {
    public static Router.GoalProvider forModel(InputModel m, int dn) {
        return ru.lct.heatnet.solver.DebugAccess.tieInGoals(m, dn);
    }
}
