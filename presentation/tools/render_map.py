#!/usr/bin/env python3
"""Карта варианта в стиле слайдов презентации (из настоящей выгрузки сервиса).

    python presentation/tools/render_map.py INPUT.geojson RESULT.geojson OUT.png --variant v1 \
        --size 1200 880 [--bbox minx miny maxx maxy] [--labels] [--label-size 13] [--highlight 2 5 10] [--roads ROADS.geojson]

Слои — только из входного файла и выгрузки, без подложки. Нужен matplotlib.
"""
import argparse
import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.patheffects as pe  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402
from pyproj import Transformer  # noqa: E402
from shapely.geometry import shape  # noqa: E402
from shapely.ops import transform  # noqa: E402

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
BUILDING, EXISTING, NEW, CHAMBER, TARGET = "#DCD9EE", "#2B1250", "#FF0A54", "#4B1A7A", "#2B1250"
ROAD, WATER, RAIL = "#F1E6C8", "#D6E6F5", "#CFC6C6"


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def fill(ax, g, color, z):
    for p in getattr(g, "geoms", [g]):
        if p.geom_type == "Polygon":
            ax.fill(*p.exterior.xy, color=color, lw=0, zorder=z)
            for hole in p.interiors:
                ax.fill(*hole.xy, color="white", lw=0, zorder=z)


def width_of(dn):
    return 1.6 + 2.4 * min(1.0, max(0.0, ((dn or 65) - 65) / 135))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("result")
    ap.add_argument("out")
    ap.add_argument("--variant", default="v1")
    ap.add_argument("--size", nargs=2, type=int, default=[1200, 880])
    ap.add_argument("--bbox", nargs=4, type=float)
    ap.add_argument("--labels", action="store_true")
    ap.add_argument("--label-size", type=float, default=13, help="кегль номеров точек, px при dpi 100")
    ap.add_argument("--highlight", nargs="*", default=[], help="id точек, выделяемых кольцом")
    ap.add_argument("--roads")
    a = ap.parse_args()
    inp, res = load(a.input), load(a.result)
    dpi = 100
    fig = plt.figure(figsize=(a.size[0] / dpi, a.size[1] / dpi), dpi=dpi)
    ax = fig.add_axes([0, 0, 1, 1])
    feats = inp["features"] + (load(a.roads)["features"] if a.roads else [])
    tie_pts = []
    for f in feats:
        p = f.get("properties") or {}
        if f.get("geometry") is None:
            continue
        g = transform(TO_M, shape(f["geometry"]))
        t = p.get("restriction_type")
        if p.get("object_type") == "restriction" and g.geom_type in ("Polygon", "MultiPolygon"):
            fill(ax, g, {"oks": BUILDING, "road": ROAD, "tram_tracks": ROAD, "water": WATER, "railway": RAIL}.get(t, "#EEEEEE"),
                 1 if t != "oks" else 2)
        elif p.get("object_type") == "heat_network":
            for q in getattr(g, "geoms", [g]):
                ax.plot(*q.xy, color=EXISTING, lw=3.2, zorder=3, solid_capstyle="round")
    for f in res["features"]:
        p = f["properties"]
        if p.get("variant_id") != a.variant or f.get("geometry") is None:
            continue
        g = transform(TO_M, shape(f["geometry"]))
        if p.get("object_type") == "heat_network":
            ax.plot(*g.xy, color=NEW, lw=width_of(p.get("diameter")), zorder=4, solid_capstyle="round", solid_joinstyle="round")
        elif p.get("object_type") == "heat_chamber":
            if p.get("diag_tie_in"):
                tie_pts.append(g)
            else:
                ax.plot(g.x, g.y, "s", ms=7, color=CHAMBER, zorder=6)
    for g in tie_pts:
        ax.plot(g.x, g.y, "D", ms=9, mfc="white", mec=CHAMBER, mew=2, zorder=7)
    for f in inp["features"]:
        p = f.get("properties") or {}
        if p.get("object_type") == "oks_connection_point" and f.get("geometry"):
            g = transform(TO_M, shape(f["geometry"]))
            ax.plot(g.x, g.y, "o", ms=7, color=TARGET, zorder=8)
            if str(p.get("id")) in a.highlight:
                ax.plot(g.x, g.y, "o", ms=20, mfc="none", mec=NEW, mew=2.5, zorder=8)
            if a.labels:
                ax.annotate(str(p.get("id")), (g.x, g.y), xytext=(6, 6), textcoords="offset points", fontsize=a.label_size,
                            color=TARGET, zorder=9, fontweight="bold",
                            path_effects=[pe.withStroke(linewidth=3, foreground="white")])
    ax.set_aspect("equal")
    if a.bbox:
        ax.set_xlim(a.bbox[0], a.bbox[2])
        ax.set_ylim(a.bbox[1], a.bbox[3])
    ax.axis("off")
    fig.savefig(a.out, dpi=dpi, facecolor="white")


if __name__ == "__main__":
    main()
