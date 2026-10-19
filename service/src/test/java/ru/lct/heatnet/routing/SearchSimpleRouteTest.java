package ru.lct.heatnet.routing;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.model.InputObjects.Restriction;
import ru.lct.heatnet.model.InputObjects.Target;
import ru.lct.heatnet.rules.RestrictionRule;

import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SearchSimpleRouteTest {
    private static final GeometryFactory GF = new GeometryFactory();
    private static Coordinate c(double x, double y) { return new Coordinate(x, y); }
    private static Polygon rect(double x, double y, double w, double h) {
        return GF.createPolygon(new Coordinate[]{c(x,y),c(x+w,y),c(x+w,y+h),c(x,y+h),c(x,y)});
    }

    @Test
    void searchCannotRevisitVertexToSatisfyEndpointTurns() {
        InputModel m = new InputModel(GF);
        m.roi = rect(-100,-100,200,200);
        double[][] boxes = {{-29,44,20,8},{38,-66,6,24},{-16,-16,22,23}};
        for (int i = 0; i < boxes.length; i++) {
            double[] b = boxes[i];
            m.restrictions.add(new Restriction(FeatureId.of(i), "park", RestrictionRule.PARK, rect(b[0],b[1],b[2],b[3])));
        }
        Router router = new Router(new ObstacleField(m,100));
        Coordinate start = c(22,30), end = c(29,-65);
        Coordinate out = c(21.152189239855474,30.530298892119493);
        Coordinate in = c(29.903863611639768,-64.57217927638611);
        DynamicObstacles dyn = new DynamicObstacles(GF);
        dyn.add(GF.createLineString(new Coordinate[]{start,out}),1.0);
        dyn.add(GF.createLineString(new Coordinate[]{end,in}),1.0);
        Router.Goal goal = new Router.Goal(end,0,null,null,2,in);
        Router.GoalProvider goals = new Router.GoalProvider() {
            public List<Router.Goal> near(Coordinate p) { return Collections.singletonList(goal); }
            public double lowerBoundDistance(Coordinate p) { return p.distance(end); }
        };
        Router.Result result = router.routeFromNode(new Target(FeatureId.of("pseudo"),1,start,GF.createPoint(start)),
                dyn,goals,2,Collections.singletonList(out));
        if (result.route != null) {
            LineString line = GF.createLineString(result.route.points.toArray(new Coordinate[0]));
            assertTrue(line.isSimple(), "search returned a loop: " + line);
        } else {
            assertNotNull(result.failure, "no route must have an explicit reason");
        }
    }
}
