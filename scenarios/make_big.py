#!/usr/bin/env python3
"""Большой вход для проверки потоковой обработки: конкурсный набор + N мелких зданий вокруг.

    ../validator/.venv/bin/python make_big.py ВЫХОД.geojson N [--near-share 0.02]

Пишется потоком, в память целиком не собирается. Здания — квадраты 12×12 м на сетке 20 × 20 км вокруг набора,
доля near-share — внутри района целей (часть попадёт в окна поиска).
"""
import argparse
import json
import random

from pyproj import Transformer

TO_LL = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
CX, CY = 414300.0, 6173300.0  # центр конкурсного района, м EPSG:32637


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("n", type=int)
    ap.add_argument("--near-share", type=float, default=0.02)
    ap.add_argument("--dataset", default="../task/sources/Датасет скорректированный.geojson")
    a = ap.parse_args()
    rnd = random.Random(42)
    base = json.load(open(a.dataset, encoding="utf-8"))
    with open(a.out, "w", encoding="utf-8") as f:
        f.write('{"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},"features":[\n')
        for feat in base["features"]:
            f.write(json.dumps(feat, ensure_ascii=False))
            f.write(",\n")
        occupied = []
        for i in range(a.n):
            if rnd.random() < a.near_share:
                # ближний район — только вдали от целей и сети не проверяем: ставим в 1,2..1,6 км от центра
                ang = rnd.random() * 6.283
                r = 1200 + rnd.random() * 400
                x, y = CX + r * __import__("math").cos(ang), CY + r * __import__("math").sin(ang)
            else:
                x = CX + (rnd.random() - 0.5) * 20000
                y = CY + (rnd.random() - 0.5) * 20000
                if abs(x - CX) < 1500 and abs(y - CY) < 1500:
                    continue
            ring = [TO_LL.transform(x + dx, y + dy) for dx, dy in ((0, 0), (12, 0), (12, 12), (0, 12), (0, 0))]
            feat = {"type": "Feature", "properties": {"id": 1_000_000 + i, "object_type": "restriction", "restriction_type": "oks"},
                    "geometry": {"type": "Polygon", "coordinates": [[[round(c[0], 9), round(c[1], 9)] for c in ring]]}}
            f.write(json.dumps(feat))
            f.write(",\n")
        # закрывающий объект без запятой после
        f.write(json.dumps({"type": "Feature", "properties": {"id": 999_999_999, "object_type": "restriction",
                                                                "restriction_type": "park"},
                            "geometry": {"type": "Polygon", "coordinates": [[list(TO_LL.transform(CX + 9000, CY + 9000)),
                                                                              list(TO_LL.transform(CX + 9010, CY + 9000)),
                                                                              list(TO_LL.transform(CX + 9010, CY + 9010)),
                                                                              list(TO_LL.transform(CX + 9000, CY + 9000))]]}}))
        f.write("\n]}\n")


if __name__ == "__main__":
    main()
