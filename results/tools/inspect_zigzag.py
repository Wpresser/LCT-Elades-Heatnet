#!/usr/bin/env python3
"""Read-only release review: delete each warned vertex in a temporary copy and run both validators."""
import copy
import json
import math
from pathlib import Path
import sys
import tempfile
from pyproj import Transformer
from shapely.geometry import LineString
import lct_audit as audit

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "validator"))
import validator as validator


def main():
    inp = ROOT / "task/sources/Датасет скорректированный.geojson"
    src = ROOT / "results/final_strict.geojson"
    doc = json.loads(src.read_text(encoding="utf-8"))
    transform = Transformer.from_crs(4326, 32637, always_xy=True)
    records = []
    with tempfile.TemporaryDirectory(prefix="heatnet-zigzag-") as directory:
        candidate_path = Path(directory) / "candidate.geojson"
        for index, feature in enumerate(doc["features"]):
            props = feature["properties"]
            if props.get("object_type") != "heat_network":
                continue
            coords = feature["geometry"]["coordinates"]
            xy = [transform.transform(*c[:2]) for c in coords]
            for vertex in range(1, len(xy)-1):
                a, b, c = xy[vertex-1:vertex+2]
                u, v = (b[0]-a[0], b[1]-a[1]), (c[0]-b[0], c[1]-b[1])
                length_u, length_v = math.hypot(*u), math.hypot(*v)
                if not length_u or not length_v:
                    continue
                turn = math.degrees(math.acos(max(-1, min(1, (u[0]*v[0]+u[1]*v[1])/(length_u*length_v)))))
                if min(length_u, length_v) >= 1 or turn <= 5:
                    continue
                candidate = copy.deepcopy(doc)
                line = candidate["features"][index]
                del line["geometry"]["coordinates"][vertex]
                new_length = LineString([transform.transform(*p[:2]) for p in line["geometry"]["coordinates"]]).length
                ratio = new_length / props["length"]
                line["properties"]["length"] = new_length
                line["properties"]["cost"] *= ratio
                summary = next(f["properties"] for f in candidate["features"] if f["properties"].get("object_type") == "variant_summary" and f["properties"]["variant_id"] == props["variant_id"])
                summary["new_network_length"] += new_length - props["length"]
                summary["construction_cost"] += line["properties"]["cost"] - props["cost"]
                summary["calculated_cost"] = summary["construction_cost"] + summary["unconnected_penalty"]
                summary["score"] = 0.7 * summary["calculated_cost"] / 25000000 + 0.3 * summary["new_network_length"] / 100
                candidate_path.write_text(json.dumps(candidate), encoding="utf-8")
                ar = audit.run(inp, candidate_path, "strict")
                vr, _ = validator.validate(inp, candidate_path)
                errors = [item for item in ar["items"] if item["level"] in ("ERROR", "AMBIGUOUS")]
                records.append({"variant": props["variant_id"], "segment": props["id"], "vertex_index": vertex,
                                "turn_degrees": turn, "adjacent_length_m": min(length_u, length_v),
                                "candidate_length_saving_m": props["length"] - new_length,
                                "audit_errors": errors, "validator_errors": vr.errors(),
                                "safe_to_remove": not errors and not vr.errors()})
    out = ROOT / "results/checks/zigzag_review.json"
    out.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for record in records:
        print(record["segment"], record["vertex_index"], "safe" if record["safe_to_remove"] else "retain",
              [item["code"] for item in record["audit_errors"]], [item["code"] for item in record["validator_errors"]])
    print("Review evidence written; authoritative geometry was not modified.")


if __name__ == "__main__":
    main()
