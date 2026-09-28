#!/usr/bin/env python3
"""Картинка результата для проверки глазами и для презентации.

    python plot.py ВХОД.geojson РЕЗУЛЬТАТ.geojson картинка.png [--variant v1] [--bbox x0 y0 x1 y1]

Координаты на картинке — метры EPSG:32637.
"""
import argparse
import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from shapely.geometry import shape  # noqa: E402

from validator import to_metric  # noqa: E402

COLORS = {"oks": "#c8c8c8", "water": "#9ecae1", "railway": "#bcbddc", "park": "#c7e9c0", "road": "#fdd0a2",
          "tram_tracks": "#fdae6b", "social_area": "#fcbba1", "prohibited_site": "#fb6a4a"}


def draw_geom(ax, g, **kw):
    if g.geom_type in ("Polygon", "MultiPolygon"):
        for p in getattr(g, "geoms", [g]):
            x, y = p.exterior.xy
            ax.fill(x, y, **kw)
    elif g.geom_type in ("LineString", "MultiLineString"):
        for ln in getattr(g, "geoms", [g]):
            x, y = ln.xy
            ax.plot(x, y, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("png")
    ap.add_argument("--variant")
    ap.add_argument("--bbox", type=float, nargs=4)
    ap.add_argument("--labels", action="store_true", help="подписать цели")
    a = ap.parse_args()
    inp = json.load(open(a.input, encoding="utf-8"))
    out = json.load(open(a.output, encoding="utf-8"))
    fig, ax = plt.subplots(figsize=(14, 12), dpi=110)
    for f in inp["features"]:
        p = f["properties"]
        g = to_metric(shape(f["geometry"]))
        t = p.get("object_type")
        if t == "restriction":
            draw_geom(ax, g, color=COLORS.get(p.get("restriction_type"), "#eeeeee"), alpha=0.8, lw=0)
        elif t == "heat_network":
            draw_geom(ax, g, color="#08519c", lw=2.5, alpha=0.7)
        elif t == "heat_chamber":
            ax.plot(g.x, g.y, "s", color="#08519c", ms=6)
        elif t == "source":
            ax.plot(g.x, g.y, "*", color="#d94801", ms=14)
        elif t == "oks_connection_point":
            ax.plot(g.x, g.y, "o", color="#a50f15", ms=5)
            if a.labels:
                ax.annotate(str(p["id"]), (g.x, g.y), fontsize=8, xytext=(3, 3), textcoords="offset points")
    variants = sorted({f["properties"]["variant_id"] for f in out["features"]}, key=str)
    vid = a.variant or (variants[0] if variants else None)
    for f in out["features"]:
        p = f["properties"]
        if p.get("variant_id") != vid or f.get("geometry") is None:
            continue
        g = to_metric(shape(f["geometry"]))
        t = p["object_type"]
        if t == "heat_network":
            special = p.get("laying_method") == "special"
            draw_geom(ax, g, color="#e31a1c" if special else "#31a354", lw=1.0 + p["diameter"] / 150.0)
        elif t == "heat_chamber":
            ax.plot(g.x, g.y, "D", color="#006d2c", ms=6)
        elif t == "technical_node":
            ax.plot(g.x, g.y, "|", color="#e31a1c", ms=8)
    summ = next((f["properties"] for f in out["features"]
                 if f["properties"].get("variant_id") == vid and f["properties"]["object_type"] == "variant_summary"), {})
    ax.set_title(f"Вариант {vid}: score {summ.get('score', 0):.4f}, длина {summ.get('new_network_length', 0):.0f} м, "
                 f"не подключены {summ.get('unconnected_oks_ids')}")
    ax.set_aspect("equal")
    if a.bbox:
        ax.set_xlim(a.bbox[0], a.bbox[2])
        ax.set_ylim(a.bbox[1], a.bbox[3])
    fig.tight_layout()
    fig.savefig(a.png)


if __name__ == "__main__":
    main()
