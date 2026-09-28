package ru.lct.heatnet.routing;

import ru.lct.heatnet.model.FeatureId;
import ru.lct.heatnet.rules.RestrictionRule;

/** Участок спецпрохода на отрезке трассы: [from, to] — расстояния от начала отрезка, м. */
public final class SpecialSpan {

    public final double from;
    public final double to;
    public final RestrictionRule rule;
    public final FeatureId objectId;

    public SpecialSpan(double from, double to, RestrictionRule rule, FeatureId objectId) {
        this.from = from;
        this.to = to;
        this.rule = rule;
        this.objectId = objectId;
    }

    public SpecialSpan reversed(double length) {
        return new SpecialSpan(length - to, length - from, rule, objectId);
    }

    public String label() {
        return rule.code + ":" + objectId;
    }
}
