#!/usr/bin/env python3
"""Синтетические входы для проверки обязательных типов ограничений (таблица 2), которых нет в конкурсном наборе.

    ../validator/.venv/bin/python make_scenarios.py

Каждый сценарий — каталог с input.geojson и expected.json. Координаты задаются в метрах EPSG:32637
около конкурсного района и переводятся в WGS 84. Ожидания — минимальные утверждения, которые проверяет
check_scenarios.py вместе с валидатором.
"""
import json
import os

from pyproj import Transformer

TO_LL = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
OX, OY = 413000.0, 6172000.0
HERE = os.path.dirname(os.path.abspath(__file__))


def ll(x, y):
    lon, lat = TO_LL.transform(OX + x, OY + y)
    return [lon, lat]


def feat(props, geom):
    return {"type": "Feature", "properties": props, "geometry": geom}


def pt(x, y):
    return {"type": "Point", "coordinates": ll(x, y)}


def line(*xy):
    return {"type": "LineString", "coordinates": [ll(x, y) for x, y in xy]}


def rect(x0, y0, x1, y1):
    return {"type": "Polygon", "coordinates": [[ll(x0, y0), ll(x1, y0), ll(x1, y1), ll(x0, y1), ll(x0, y0)]]}


def pipe(fid, dn, *xy):
    return feat({"id": fid, "object_type": "heat_network", "diameter": dn}, line(*xy))


def building(fid, x0, y0, x1, y1):
    return feat({"id": fid, "object_type": "restriction", "restriction_type": "oks"}, rect(x0, y0, x1, y1))


def target(fid, x, y, flow):
    return feat({"id": fid, "object_type": "oks_connection_point", "flow_tph": flow}, pt(x, y))


def restriction(fid, rtype, geom):
    return feat({"id": fid, "object_type": "restriction", "restriction_type": rtype}, geom)


def base(extra, targets=None):
    """Существующая труба вдоль y = 0 (x от -300 до 300), источник, здание с целью севернее."""
    feats = [
        feat({"id": 1, "object_type": "source"}, pt(-300, 0)),
        pipe(2, 400, (-300, 0), (300, 0)),
        building(10, -15, 90, 15, 110),
    ]
    feats += targets if targets is not None else [target(100, 0, 95, 30.0)]
    return {"type": "FeatureCollection", "features": feats + extra}


SCENARIOS = {
    # дорога поперёк: спецпроход, угол ≥ 45°, полигон + 3 м, K = 1,60
    "road_crossing": (base([restriction(20, "road", rect(-300, 40, 300, 55))]),
                      {"connected": [100], "special_types": ["road"]}),
    "tram_crossing": (base([restriction(21, "tram_tracks", rect(-300, 40, 300, 50))]),
                      {"connected": [100], "special_types": ["tram_tracks"]}),
    # газопровод и кабель — линии: спецпроход ± 2 м (+ зона отступа, DECISIONS №28)
    "gas_crossing": (base([restriction(22, "gas_pipeline", line((-300, 45), (300, 45)))]),
                     {"connected": [100], "special_types": ["gas_pipeline"]}),
    "cable_crossing": (base([restriction(23, "power_cable", line((-300, 45), (300, 45)))]),
                       {"connected": [100], "special_types": ["power_cable"]}),
    # запретные площадные объекты между целью и сетью — обход с отступом 1 м
    "park_between": (base([restriction(24, "park", rect(-60, 20, 60, 70))]),
                     {"connected": [100], "special_types": []}),
    "social_and_prohibited": (base([restriction(25, "social_area", rect(-60, 20, -2, 70)),
                                    restriction(26, "prohibited_site", rect(2, 20, 60, 70))]),
                              {"connected": [100], "special_types": []}),
    # дорога и газопровод в ней: наложение спецпроходов, K = max, деление при смене набора
    "road_with_gas": (base([restriction(27, "road", rect(-300, 40, 300, 60)),
                            restriction(28, "gas_pipeline", line((-300, 50), (300, 50)))]),
                      {"connected": [100], "special_types": ["road", "gas_pipeline"]}),
    # пересечение существующей теплосети без врезки: толстая труба ДУ 1200 ближе (камера 12 млн),
    # тонкая ДУ 300 дальше (камера 5 млн) — выгоднее пересечь толстую (K = 1,05)
    "cross_existing_network": ({"type": "FeatureCollection", "features": [
        feat({"id": 1, "object_type": "source"}, pt(-300, 0)),
        pipe(2, 300, (-300, 0), (300, 0)),
        pipe(3, 1200, (-300, 0), (-300, 25), (300, 25)),
        building(10, -15, 90, 15, 110),
        target(100, 0, 95, 30.0),
    ]}, {"connected": [100], "special_types": ["heat_network"]}),
    # цель, окружённая водой, не подключается; соседняя — подключается (частичный результат)
    "unreachable_island": (base([
        restriction(29, "water", {"type": "Polygon", "coordinates": [
            [ll(100, 60), ll(200, 60), ll(200, 160), ll(100, 160), ll(100, 60)],
            [ll(120, 80), ll(180, 80), ll(180, 140), ll(120, 140), ll(120, 80)]]}),
        building(11, 140, 100, 160, 120),
    ], targets=[target(100, 0, 95, 30.0), target("island", 150, 105, 10.0)]),
        {"connected": [100], "unconnected": ["island"], "special_types": []}),
    # строковые ID и два потребителя в одном здании
    "string_ids_two_in_one": ({"type": "FeatureCollection", "features": [
        feat({"id": "src", "object_type": "source"}, pt(-300, 0)),
        pipe("p1", 400, (-300, 0), (300, 0)),
        building("b1", -30, 60, 30, 90),
        target("a", -20, 65, 20.0),
        target("b", 20, 65, 25.0),
    ]}, {"connected": ["a", "b"], "special_types": []}),
    # точка подключения не внутри здания — финальный подход не нужен, трасса идёт прямо от точки
    "target_outside_building": ({"type": "FeatureCollection", "features": [
        feat({"id": 1, "object_type": "source"}, pt(-300, 0)),
        pipe(2, 400, (-300, 0), (300, 0)),
        building(10, -40, 60, 40, 90),
        target(100, 0, 120, 15.0),
    ]}, {"connected": [100], "special_types": []}),
    # «грязный» вход: 3D-координаты, повторяющиеся точки трубы, ДУ не из таблицы, самопересекающийся полигон,
    # id-строки из цифр, объект без геометрии, неизвестный тип ограничения
    "dirty_input": ({"type": "FeatureCollection", "features": [
        feat({"id": "1", "object_type": "source"}, {"type": "Point", "coordinates": ll(-300, 0) + [150.0]}),
        feat({"id": "2", "object_type": "heat_network", "diameter": 350},
             {"type": "LineString", "coordinates": [ll(-300, 0) + [150.0], ll(0, 0) + [150.0], ll(0, 0) + [150.0], ll(300, 0) + [151.0]]}),
        building("10", -15, 90, 15, 110),
        feat({"id": "11", "object_type": "restriction", "restriction_type": "oks"},
             {"type": "Polygon", "coordinates": [[ll(60, 40), ll(90, 70), ll(90, 40), ll(60, 70), ll(60, 40)]]}),
        feat({"id": "12", "object_type": "restriction", "restriction_type": "metro_entrance"}, rect(-80, 40, -60, 60)),
        feat({"id": "13", "object_type": "oks_connection_point", "flow_tph": 12.5}, None),
        target("100", 0, 95, 30.0),
    ]}, {"connected": ["100"], "special_types": []}),
    # существующая труба лежит под дорогой (внутри полигона): спецпроход заканчивается в камере присоединения
    "pipe_under_road": (base([restriction(40, "road", rect(-300, -12, 300, 12))]),
                        {"connected": [100], "special_types": ["road"]}),
    # нет существующей сети — все цели не подключены, результат всё равно формируется
    "no_network": ({"type": "FeatureCollection", "features": [
        building(10, -15, 90, 15, 110),
        target(100, 0, 95, 30.0),
    ]}, {"unconnected": [100], "special_types": []}),
}


def main():
    for name, (fc, expected) in SCENARIOS.items():
        d = os.path.join(HERE, name)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "input.geojson"), "w", encoding="utf-8") as f:
            json.dump(fc, f, ensure_ascii=False)
        with open(os.path.join(d, "expected.json"), "w", encoding="utf-8") as f:
            json.dump(expected, f, ensure_ascii=False, indent=2)
        print("сценарий", name)


if __name__ == "__main__":
    main()
