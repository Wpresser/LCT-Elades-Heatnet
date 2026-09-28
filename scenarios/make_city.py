#!/usr/bin/env python3
"""Синтетический «город» для проверки масштаба: сетка кварталов с домами, сеть по улицам, N целей.

    ../validator/.venv/bin/python make_city.py ВЫХОД.geojson --targets 80 --blocks 12

Кварталы 60×60 м, улицы 20 м; существующая сеть — по двум улицам (вертикальной и горизонтальной);
цели — в случайных домах, по одной на дом.
"""
import argparse
import json
import random

from pyproj import Transformer

TO_LL = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
OX, OY = 420000.0, 6180000.0


def ll(x, y):
    lon, lat = TO_LL.transform(OX + x, OY + y)
    return [lon, lat]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--targets", type=int, default=80)
    ap.add_argument("--blocks", type=int, default=12)
    a = ap.parse_args()
    rnd = random.Random(7)
    step, block = 80.0, 60.0
    feats = []
    fid = 1
    houses = []
    for i in range(a.blocks):
        for j in range(a.blocks):
            x0, y0 = i * step, j * step
            # в квартале 4 дома 24×24 м с проездами 12 м
            for dx in (0, 36):
                for dy in (0, 36):
                    hx, hy = x0 + dx, y0 + dy
                    ring = [ll(hx, hy), ll(hx + 24, hy), ll(hx + 24, hy + 24), ll(hx, hy + 24), ll(hx, hy)]
                    feats.append({"type": "Feature", "properties": {"id": fid, "object_type": "restriction", "restriction_type": "oks"},
                                  "geometry": {"type": "Polygon", "coordinates": [ring]}})
                    houses.append((hx, hy))
                    fid += 1
    mid = (a.blocks // 2) * step - 10  # середина улицы
    span = a.blocks * step
    feats.append({"type": "Feature", "properties": {"id": fid, "object_type": "source"}, "geometry": {"type": "Point", "coordinates": ll(mid, -10)}})
    fid += 1
    feats.append({"type": "Feature", "properties": {"id": fid, "object_type": "heat_network", "diameter": 500},
                  "geometry": {"type": "LineString", "coordinates": [ll(mid, -10), ll(mid, span)]}})
    fid += 1
    feats.append({"type": "Feature", "properties": {"id": fid, "object_type": "heat_network", "diameter": 400},
                  "geometry": {"type": "LineString", "coordinates": [ll(mid, mid), ll(span, mid)]}})
    fid += 1
    for hx, hy in rnd.sample(houses, a.targets):
        feats.append({"type": "Feature", "properties": {"id": fid, "object_type": "oks_connection_point",
                                                        "flow_tph": round(rnd.uniform(5, 40), 2)},
                      "geometry": {"type": "Point", "coordinates": ll(hx + 12 + rnd.uniform(-5, 5), hy + 3)}})
        fid += 1
    json.dump({"type": "FeatureCollection", "features": feats}, open(a.out, "w"))
    print(f"домов {len(houses)}, целей {a.targets}")


if __name__ == "__main__":
    main()
