#!/usr/bin/env python3
"""Generate/check FINAL_METRICS.json directly against authoritative summaries."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "task/sources/Датасет скорректированный.geojson"


def summaries(path):
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    return sorted((f["properties"] for f in document["features"]
                   if f["properties"].get("object_type") == "variant_summary"), key=lambda p: p["rank"])


def artifact_metrics():
    targets = sum(f.get("properties", {}).get("object_type") == "oks_connection_point"
                  for f in json.loads(DATASET.read_text(encoding="utf-8"))["features"])
    data = {"schema_version": 2, "dataset_sha256": hashlib.sha256(DATASET.read_bytes()).hexdigest(),
            "cost_definition": "construction_cost = pipes + new chambers + existing chamber tie-ins; calculated_cost = construction_cost + unconnected_penalty",
            "default_policy": "literal", "official_clarification_received": False}
    for mode, policy in [("strict", "literal"), ("alternative", "relaxed")]:
        path = ROOT / f"results/final_{mode}.geojson"
        rows = summaries(path)
        variants = {}
        for p in rows:
            assert p["diag_terminal_policy"] == policy, f"{mode}: wrong terminal policy"
            assert math.isclose(p["construction_cost"] + p["unconnected_penalty"], p["calculated_cost"], abs_tol=0.01)
            assert math.isclose(p["score"], 0.7 * p["calculated_cost"] / 25000000 + 0.3 * p["new_network_length"] / 100, abs_tol=1e-10)
            assert not p.get("diag_search_truncated"), f"{mode}: truncated search"
            variants[p["variant_id"]] = {
                "rank": p["rank"], "coverage": f"{targets-len(p['unconnected_oks_ids'])}/{targets}",
                "construction_cost": p["construction_cost"], "unconnected_penalty": p["unconnected_penalty"],
                "calculated_cost": p["calculated_cost"], "length_m": p["new_network_length"],
                "score": p["score"], "unconnected_ids": p["unconnected_oks_ids"],
            }
        data[mode] = {"policy": policy, **variants[rows[0]["variant_id"]],
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "variants": variants}
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    expected = artifact_metrics()
    path = ROOT / "FINAL_METRICS.json"
    if args.write:
        path.write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert json.loads(path.read_text(encoding="utf-8")) == expected, "Stale FINAL_METRICS.json: run python scripts/check_release_metrics.py --write"
    print("FINAL_METRICS.json matches both authoritative GeoJSON summaries and hashes")


if __name__ == "__main__":
    main()
