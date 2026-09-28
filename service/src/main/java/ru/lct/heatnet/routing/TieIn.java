package ru.lct.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import ru.lct.heatnet.model.InputObjects.ExistingChamber;
import ru.lct.heatnet.model.InputObjects.ExistingPipe;

/** Кандидат места присоединения к существующей сети (§2.4). */
public final class TieIn {

    public enum Kind {
        /** Врезка в существующую камеру: 5 млн за каждый новый участок. */
        EXISTING_CHAMBER,
        /** Новая камера на существующей трубе. */
        NEW_ON_PIPE
    }

    public final Kind kind;
    public final Coordinate point;
    public final ExistingChamber chamber;
    public final ExistingPipe pipe;
    /** Стоимость камеры или врезки, руб. */
    public final double cost;
    /** Наибольший ДУ существующих труб в точке (для диаметра новой камеры). */
    public final int existingMaxDn;

    public TieIn(Kind kind, Coordinate point, ExistingChamber chamber, ExistingPipe pipe, double cost, int existingMaxDn) {
        this.kind = kind;
        this.point = point;
        this.chamber = chamber;
        this.pipe = pipe;
        this.cost = cost;
        this.existingMaxDn = existingMaxDn;
    }

    @Override
    public String toString() {
        return kind == Kind.EXISTING_CHAMBER ? "камера " + chamber.id : "новая камера на трубе " + pipe.id;
    }
}
