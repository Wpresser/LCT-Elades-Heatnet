#!/usr/bin/env python3
"""Sync authoritative GeoJSON bytes, or fail on stale/missing viewer copies."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "strict.geojson": "results/final_strict.geojson",
    "alternative.geojson": "results/final_alternative.geojson",
    "input.geojson": "task/sources/Датасет скорректированный.geojson",
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sync(root=ROOT, check=False):
    dest = root / "viewer/public/data"
    errors = []
    hashes = {}
    for name, source in SOURCES.items():
        src, dst = root / source, dest / name
        hashes[name] = sha256(src)
        if not check:
            dest.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(src.read_bytes())
        if not dst.is_file() or sha256(dst) != hashes[name]:
            errors.append(f"missing/stale {dst.relative_to(root)} (source: {source})")
    # Optional proof is self-contained. The manifest prevents missing-file requests.
    optional = []
    proof = dest / "proof.json"
    if proof.is_file():
        records = json.loads(proof.read_text(encoding="utf-8"))
        if not isinstance(records, list):
            raise ValueError("proof.json must be an array")
        optional.append("proof.json")
        for record in records:
            name = record.get("visual_geojson")
            if name and Path(name).name == name and (dest / name).is_file():
                visual = json.loads((dest / name).read_text(encoding="utf-8"))
                if visual.get("type") == "FeatureCollection":
                    optional.append(name)
    manifest = {"sha256": hashes, "optional": sorted(set(optional))}
    path = dest / "manifest.json"
    if not check:
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    if not path.is_file() or json.loads(path.read_text(encoding="utf-8")) != manifest:
        errors.append("missing/stale viewer/public/data/manifest.json")
    if errors:
        raise ValueError("\n".join(errors) + "\nRun python scripts/sync_viewer_data.py")
    return hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, digest in sync(check=args.check).items():
        print(f"{name}: {digest}")


if __name__ == "__main__":
    main()
