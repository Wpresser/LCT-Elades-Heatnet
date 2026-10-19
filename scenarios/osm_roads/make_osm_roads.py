#!/usr/bin/env python3
"""Обогащённая копия конкурсного набора: + полигоны автомобильных дорог из OpenStreetMap (замечание экспертов).

    python scenarios/osm_roads/make_osm_roads.py OVERPASS_RAW.json "task/sources/Датасет скорректированный.geojson" \
        scenarios/osm_roads --fetched-at 2026-10-18T19:05:00+0300 --source-url https://overpass.kumi.systems/api/interpreter

Исходный конкурсный файл не меняется: пишется отдельный enriched.geojson (все исходные объекты + новые
restriction_type=road) и manifest.json с источником, временем, запросом, хешами и допущениями. Имя не input.geojson,
чтобы набор не попадал в быстрые ScenarioTest/check_scenarios: он тяжёлый, его гоняют отдельно (README рядом).

Допущение: площадных полигонов проезжей части в OSM для этого района нет, поэтому полигон дороги строится буфером
линии highway на половину ширины: тег width, иначе lanes × 3,5 м, иначе типовая ширина класса (WIDTH_BY_CLASS).
Из полигонов вычитаются здания и другие ограничения набора (дорога под аркой, мост) — пересечение ограничений разных
типов не моделируется. Данные © участники OpenStreetMap, лицензия ODbL: https://www.openstreetmap.org/copyright
"""
import argparse
import hashlib
import json
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, mapping, shape
from shapely.ops import transform, unary_union

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
TO_LL = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True).transform
LANE_M = 3.5
WIDTH_BY_CLASS = {
    "motorway": 21.0, "trunk": 17.5, "primary": 14.0, "secondary": 10.5, "tertiary": 7.0,
    "unclassified": 6.0, "residential": 6.0, "living_street": 5.0, "service": 4.5,
    "motorway_link": 5.0, "trunk_link": 5.0, "primary_link": 5.0, "secondary_link": 5.0, "tertiary_link": 5.0,
}
WIDTH_BY_SERVICE = {"driveway": 3.5, "alley": 3.5, "parking_aisle": 5.0}
MIN_AREA_M2 = 20.0


def width_of(tags):
    try:
        return float(tags["width"]), "width"
    except (KeyError, ValueError):
        pass
    try:
        return float(tags["lanes"]) * LANE_M, "lanes"
    except (KeyError, ValueError):
        pass
    if tags.get("highway") == "service" and tags.get("service") in WIDTH_BY_SERVICE:
        return WIDTH_BY_SERVICE[tags["service"]], "class"
    return WIDTH_BY_CLASS.get(tags.get("highway")), "class"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("raw")
    ap.add_argument("dataset")
    ap.add_argument("outdir")
    ap.add_argument("--fetched-at", required=True)
    ap.add_argument("--source-url", required=True)
    ap.add_argument("--query")
    a = ap.parse_args()
    raw = json.loads(Path(a.raw).read_text(encoding="utf-8"))
    data = json.loads(Path(a.dataset).read_text(encoding="utf-8"))

    blocked = []
    extent = []
    for f in data["features"]:
        if f.get("geometry") is None:
            continue
        g = transform(TO_M, shape(f["geometry"]))
        extent.append(g)
        if (f.get("properties") or {}).get("object_type") == "restriction" and g.geom_type in ("Polygon", "MultiPolygon"):
            blocked.append(g.buffer(0))
    area = unary_union(extent).convex_hull.buffer(30)
    blocked_union = unary_union(blocked)

    buffers, used, widths = [], [], {"width": 0, "lanes": 0, "class": 0}
    for e in raw["elements"]:
        tags = e.get("tags", {})
        if e.get("type") != "way" or "highway" not in tags or "geometry" not in e:
            continue
        w, how = width_of(tags)
        if not w:
            continue
        line = transform(TO_M, LineString([(p["lon"], p["lat"]) for p in e["geometry"]]))
        if not line.intersects(area):
            continue
        buffers.append(line.buffer(w / 2, cap_style=2, join_style=2))
        used.append(e["id"])
        widths[how] += 1
    roads = unary_union(buffers).intersection(area).difference(blocked_union)
    polys = [p for p in getattr(roads, "geoms", [roads]) if p.geom_type == "Polygon" and p.area >= MIN_AREA_M2]

    out = Path(a.outdir)
    out.mkdir(parents=True, exist_ok=True)
    features = list(data["features"])
    for i, p in enumerate(sorted(polys, key=lambda q: (-q.area, q.centroid.x)), 1):
        features.append({"type": "Feature", "properties": {
            "id": f"osm_road_{i}", "object_type": "restriction", "restriction_type": "road",
            "source": "OpenStreetMap contributors, ODbL (approximate width, see manifest.json)"},
            "geometry": mapping(transform(TO_LL, p.simplify(0.05)))})
    enriched = dict(data)
    enriched["features"] = features
    (out / "enriched.geojson").write_text(json.dumps(enriched, ensure_ascii=False), encoding="utf-8")
    manifest = {
        "purpose": "competition dataset + OSM road polygons (expert feedback: corridors between buildings and roads, "
                   "speed on a dataset with roads); the original file is unchanged",
        "base_dataset": {"file": Path(a.dataset).name, "sha256": sha256(a.dataset)},
        "osm": {"source_url": a.source_url, "fetched_at": a.fetched_at,
                "timestamp_osm_base": raw.get("osm3s", {}).get("timestamp_osm_base"),
                "query": Path(a.query).read_text(encoding="utf-8").strip() if a.query else None,
                "raw_sha256": sha256(a.raw), "ways_used": len(used),
                "attribution": "© OpenStreetMap contributors, ODbL 1.0, https://www.openstreetmap.org/copyright"},
        "conversion": {"width_source_counts": widths, "lane_m": LANE_M, "width_by_class": WIDTH_BY_CLASS,
                       "width_by_service": WIDTH_BY_SERVICE, "subtracted": "all polygon restrictions of the base dataset",
                       "min_area_m2": MIN_AREA_M2, "assumption": "road polygon = highway centre line buffered by half width"},
        "road_polygons": len(polys),
        "road_area_m2": round(sum(p.area for p in polys), 1),
        "output_sha256": sha256(out / "enriched.geojson"),
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"ways {len(used)}, road polygons {len(polys)}, area {manifest['road_area_m2']} m2, widths {widths}")


if __name__ == "__main__":
    main()
