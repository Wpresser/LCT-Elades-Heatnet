#!/usr/bin/env python3
"""Карта «до/после» одного варианта в одном масштабе (для отчёта и слайдов).

    python results/tools/render_compare.py INPUT.geojson BEFORE.geojson AFTER.geojson OUT.png \
        [--variant v1] [--titles "до" "после"] [--bbox minx miny maxx maxy] [--roads ROADS.geojson]

Нужен matplotlib. Координаты рисуются в EPSG:32637 (метры), подложки нет: только входные слои.
"""
import argparse
import json

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from pyproj import Transformer  # noqa: E402
from shapely.geometry import shape  # noqa: E402
from shapely.ops import transform  # noqa: E402

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
DN_COLORS = [(65, "#5fb3c9"), (100, "#4a7fd6"), (125, "#8a63d2"), (150, "#e0a43a"), (10_000, "#d9534f")]


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def draw_poly(ax, g, **kw):
    for p in getattr(g, "geoms", [g]):
        if p.geom_type != "Polygon":
            continue
        ax.fill(*p.exterior.xy, **kw)
        for hole in p.interiors:
            ax.fill(*hole.xy, color="white", lw=0)


def draw_input(ax, inp, roads):
    for f in inp["features"] + (roads["features"] if roads else []):
        p = f.get("properties") or {}
        if f.get("geometry") is None:
            continue
        g = transform(TO_M, shape(f["geometry"]))
        t = p.get("restriction_type")
        if p.get("object_type") == "restriction":
            style = {"oks": dict(color="#d7d3cc", lw=0), "water": dict(color="#bcd7ee", lw=0),
                     "railway": dict(color="#b9a7a7", lw=0), "road": dict(color="#efe2c2", lw=0),
                     "tram_tracks": dict(color="#e6c9a8", lw=0)}.get(t, dict(color="#e8e8e8", lw=0))
            if g.geom_type in ("Polygon", "MultiPolygon"):
                draw_poly(ax, g, zorder=1, **style)
            else:
                for q in getattr(g, "geoms", [g]):
                    ax.plot(*q.xy, color="#999999", lw=0.8, zorder=1)
        elif p.get("object_type") == "heat_network":
            for q in getattr(g, "geoms", [g]):
                ax.plot(*q.xy, color="#444444", lw=2.2, zorder=2, solid_capstyle="round")
        elif p.get("object_type") == "oks_connection_point":
            ax.plot(g.x, g.y, "o", ms=4.5, mfc="white", mec="#222222", mew=1.2, zorder=6)
        elif p.get("object_type") == "heat_chamber":
            ax.plot(g.x, g.y, "s", ms=3.5, color="#444444", zorder=3)


def dn_color(dn):
    return next(c for lim, c in DN_COLORS if (dn or 0) <= lim)


def draw_result(ax, res, variant):
    for f in res["features"]:
        p = f["properties"]
        if p.get("variant_id") != variant or f.get("geometry") is None:
            continue
        g = transform(TO_M, shape(f["geometry"]))
        if p.get("object_type") == "heat_network":
            ax.plot(*g.xy, color=dn_color(p.get("diameter")), lw=2.0, zorder=4, solid_capstyle="round")
        elif p.get("object_type") == "heat_chamber":
            ax.plot(g.x, g.y, "D", ms=3.8, color="#c0392b", zorder=5)


def summary(res, variant):
    return next((f["properties"] for f in res["features"]
                 if f["properties"].get("object_type") == "variant_summary" and f["properties"]["variant_id"] == variant), {})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("before")
    ap.add_argument("after")
    ap.add_argument("out")
    ap.add_argument("--variant", default="v1")
    ap.add_argument("--titles", nargs=2, default=["До", "После"])
    ap.add_argument("--bbox", nargs=4, type=float)
    ap.add_argument("--roads")
    a = ap.parse_args()
    inp = load(a.input)
    roads = load(a.roads) if a.roads else None
    fig, axes = plt.subplots(1, 2, figsize=(16, 8.2), dpi=150)
    for ax, path, title in zip(axes, (a.before, a.after), a.titles):
        res = load(path)
        draw_input(ax, inp, roads)
        draw_result(ax, res, a.variant)
        s = summary(res, a.variant)
        n_un = len(s.get("unconnected_oks_ids", []))
        length = f"{s.get('new_network_length', 0):,.0f}".replace(",", "\u00a0")
        score = f"{s.get('score', 0):.3f}".replace(".", ",")
        ax.set_title(f"{title}: L = {length} м, S = {score}" + (f", не подключено {n_un}" if n_un else ""), fontsize=12)
        ax.set_aspect("equal")
        if a.bbox:
            ax.set_xlim(a.bbox[0], a.bbox[2])
            ax.set_ylim(a.bbox[1], a.bbox[3])
        ax.set_xticks([])
        ax.set_yticks([])
    fig.tight_layout()
    fig.savefig(a.out)


if __name__ == "__main__":
    main()
