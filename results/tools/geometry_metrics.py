#!/usr/bin/env python3
"""Инженерные метрики геометрии новой сети (не влияют на официальный S).

    python results/tools/geometry_metrics.py INPUT.geojson RESULT.geojson [--json out.json]

Для каждого варианта:
- aligned_share — доля длины новой сети (без финальных участков к целям, §2.2) вдоль локальной оси застройки
  в пределах ±10°. Локальная ось — преобладающее направление стен ОКС и границ дорог в радиусе 60 м от середины
  отрезка по модулю 90°; вес — длина ребра, ближние сильнее. Реализовано здесь независимо от сервиса;
- small_turns — изломы на 0,5–15° (мелкие «зубцы»), turns — все изломы больше 0,5°;
- short_segments — отрезки короче 3 м;
- parallel_12m / parallel_30m — доля длины, идущая параллельно (±15°) другой новой линии того же варианта ближе
  12 / 30 м. Это свойство текущего файла, а не вывод о том, какой файл обсуждался на встрече.
"""
import argparse
import json
import math
from collections import defaultdict

from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import transform
from shapely.strtree import STRtree

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
AXIS_RADIUS, ALIGN_TOL, SMALL_TURN = 60.0, 10.0, 15.0


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def axis_at(p, tree, geoms):
    hist = [0.0] * 90
    total = 0.0
    for i in tree.query(p.buffer(AXIS_RADIUS)):
        g = geoms[i]
        rings = [g.boundary] if g.geom_type == "Polygon" else [q.boundary for q in g.geoms]
        for ring in rings:
            for part in getattr(ring, "geoms", [ring]):
                cs = list(part.coords)
                for a, b in zip(cs, cs[1:]):
                    seg_len = math.dist(a, b)
                    if seg_len < 1e-6:
                        continue
                    d = p.distance(Point((a[0] + b[0]) / 2, (a[1] + b[1]) / 2))
                    if d > AXIS_RADIUS:
                        continue
                    w = seg_len / (1 + d / 20.0)
                    ang = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0])) % 90
                    hist[int(ang) % 90] += w
                    total += w
    if total < 20:
        return None
    best = max(range(90), key=lambda i: sum(hist[(i + k) % 90] for k in range(-2, 3)))
    if sum(hist[(best + k) % 90] for k in range(-2, 3)) < 0.25 * total:
        return None
    sw = sum(hist[(best + k) % 90] for k in range(-2, 3))
    return (sum(hist[(best + k) % 90] * (best + k + 0.5) for k in range(-2, 3)) / sw) % 90


def turn_deg(a, b, c):
    x1, y1, x2, y2 = b[0] - a[0], b[1] - a[1], c[0] - b[0], c[1] - b[1]
    n = math.hypot(x1, y1) * math.hypot(x2, y2)
    if n == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, (x1 * x2 + y1 * y2) / n))))


def parallel_share(lines, radius):
    segs = []
    for lid, g in lines:
        cs = list(g.coords)
        segs += [(lid, LineString([a, b])) for a, b in zip(cs, cs[1:]) if math.dist(a, b) >= 1]
    total = sum(s.length for _, s in segs) or 1.0
    par = 0.0
    for i, (la, sa) in enumerate(segs):
        aa = math.degrees(math.atan2(sa.coords[1][1] - sa.coords[0][1], sa.coords[1][0] - sa.coords[0][0])) % 180
        best = 0.0
        for j, (lb, sb) in enumerate(segs):
            if la == lb or sa.distance(sb) > radius:
                continue
            ab = math.degrees(math.atan2(sb.coords[1][1] - sb.coords[0][1], sb.coords[1][0] - sb.coords[0][0])) % 180
            d = abs(aa - ab)
            if min(d, 180 - d) > 15:
                continue
            best = max(best, sa.intersection(sb.buffer(radius, cap_style=2)).length)
        if best > 2:
            par += best
    return par / total


def metrics(inp, res):
    walls = []
    targets = []
    for f in inp["features"]:
        p = f.get("properties") or {}
        if f.get("geometry") is None:
            continue
        if p.get("object_type") == "restriction" and p.get("restriction_type") in ("oks", "road", "tram_tracks"):
            g = transform(TO_M, shape(f["geometry"]))
            if g.geom_type in ("Polygon", "MultiPolygon"):
                walls.append(g)
        elif p.get("object_type") == "oks_connection_point":
            targets.append(transform(TO_M, shape(f["geometry"])))
    tree = STRtree(walls)
    by_var = defaultdict(list)
    for f in res["features"]:
        p = f["properties"]
        if p.get("object_type") == "heat_network":
            by_var[p["variant_id"]].append((p["id"], transform(TO_M, shape(f["geometry"]))))
    out = {}
    for vid, lines in sorted(by_var.items(), key=lambda x: str(x[0])):
        length = aligned = considered = 0.0
        turns = small = short = 0
        # линии одного варианта соединяются в узлах: изломы считаем внутри линий и в узлах степени 2
        ends = defaultdict(list)
        for lid, g in lines:
            cs = list(g.coords)
            length += g.length
            for a, b in zip(cs, cs[1:]):
                L = math.dist(a, b)
                if L < 3:
                    short += 1
                if any(Point(a).distance(t) < 0.05 or Point(b).distance(t) < 0.05 for t in targets):
                    continue  # финальный участок к цели (§2.2)
                ax = axis_at(Point((a[0] + b[0]) / 2, (a[1] + b[1]) / 2), tree, walls)
                if ax is None:
                    continue
                considered += L
                d = (math.degrees(math.atan2(b[1] - a[1], b[0] - a[0])) - ax) % 90
                if min(d, 90 - d) <= ALIGN_TOL:
                    aligned += L
            for a, b, c in zip(cs, cs[1:], cs[2:]):
                t = turn_deg(a, b, c)
                turns += t > 0.5
                small += 0.5 < t < SMALL_TURN
            ends[tuple(round(v, 2) for v in cs[0])].append(cs[1])
            ends[tuple(round(v, 2) for v in cs[-1])].append(cs[-2])
        for node, nbrs in ends.items():
            if len(nbrs) == 2:
                t = turn_deg(nbrs[0], node, nbrs[1])  # поворот при проходе через узел: 0° — прямо
                turns += t > 0.5
                small += 0.5 < t < SMALL_TURN
        out[str(vid)] = {
            "length_m": round(length, 1),
            "aligned_share": round(aligned / considered, 3) if considered else None,
            "turns": turns, "small_turns": small, "short_segments": short,
            "parallel_12m": round(parallel_share(lines, 12), 3), "parallel_30m": round(parallel_share(lines, 30), 3),
        }
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("result")
    ap.add_argument("--json")
    a = ap.parse_args()
    m = metrics(load(a.input), load(a.result))
    for vid, row in m.items():
        print(vid, row)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(m, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
