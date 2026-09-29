#!/usr/bin/env python3
"""Real HTTP upload/start/poll/download, semantic comparison and independent validation."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import time
import requests

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "task/sources/Датасет скорректированный.geojson"


def request(method, url, **kwargs):
    response = requests.request(method, url, timeout=30, **kwargs)
    response.raise_for_status()
    return response


def wait_ready(base, timeout):
    deadline = time.monotonic() + timeout
    while True:
        try:
            request("GET", base + "/v3/api-docs")
            break
        except requests.RequestException:
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)
    for endpoint in ["/", "/v3/api-docs", "/swagger-ui.html"]:
        response = request("GET", base + endpoint)
        print(f"GET {endpoint}: {response.status_code}", flush=True)


def compare(expected, actual, path="root"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict) and expected.keys() == actual.keys(), f"{path}: keys differ"
        for key in expected:
            compare(expected[key], actual[key], f"{path}.{key}")
    elif isinstance(expected, list):
        assert isinstance(actual, list) and len(expected) == len(actual), f"{path}: lengths differ"
        if path.endswith(".features"):
            key = lambda f: (str(f["properties"]["variant_id"]), str(f["properties"]["id"]))
            expected, actual = sorted(expected, key=key), sorted(actual, key=key)
        for index, (a, b) in enumerate(zip(expected, actual)):
            compare(a, b, f"{path}[{index}]")
    elif isinstance(expected, (float, int)) and not isinstance(expected, bool):
        tolerance = 1e-9 if ".coordinates" in path else 1e-6
        assert isinstance(actual, (float, int)) and not isinstance(actual, bool) and math.isfinite(actual)
        assert math.isclose(expected, actual, rel_tol=0 if ".coordinates" in path else 1e-12, abs_tol=tolerance), f"{path}: {expected} != {actual}"
    else:
        assert type(actual) is type(expected) and expected == actual, f"{path}: {expected!r} != {actual!r}"


def validate(output):
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    for args in [
        ["results/tools/lct_audit.py", "--input", str(DATASET), "--output", str(output), "--terminal-policy", "strict", "--json", str(output.with_suffix(".audit.json"))],
        ["validator/validator.py", str(DATASET), str(output), "--json", str(output.with_suffix(".validator.json"))],
        ["results/tools/output_contract_check.py", str(DATASET), str(output)],
    ]:
        subprocess.run([sys.executable, *args], cwd=ROOT, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path, default=ROOT / ".release-check/api-result.geojson")
    parser.add_argument("--report", type=Path, default=ROOT / ".release-check/api-e2e.json")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--verify-existing", action="store_true", help="Check persistent job after DB/app restart using saved report")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    wait_ready(base, min(args.timeout, 180))
    if args.verify_existing:
        job_id = json.loads(args.report.read_text(encoding="utf-8"))["job_id"]
    else:
        with DATASET.open("rb") as stream:
            job = request("POST", base + "/api/jobs", params={"autostart": "false"}, files={"file": (DATASET.name, stream, "application/geo+json")}).json()
        assert job["status"] == "UPLOADED", job
        job_id = job["id"]
        print(f"Upload: {job_id} ({job['inputBytes']} bytes)", flush=True)
        job = request("POST", base + f"/api/jobs/{job_id}/start").json()
        assert job["status"] in ("QUEUED", "RUNNING", "DONE"), job
    deadline = time.monotonic() + args.timeout
    while True:
        job = request("GET", base + f"/api/jobs/{job_id}").json()
        if job["status"] == "DONE":
            break
        assert job["status"] != "FAILED", job
        assert time.monotonic() < deadline, f"Timeout waiting for {job_id}: {job}"
        time.sleep(2)
    response = request("GET", base + f"/api/jobs/{job_id}/result")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(response.content)
    actual = response.json()
    reference = ROOT / "results/final_strict.geojson"
    compare(json.loads(reference.read_text(encoding="utf-8")), actual)
    rows = [f["properties"] for f in actual["features"] if f["properties"].get("object_type") == "variant_summary"]
    assert rows and all(p["diag_terminal_policy"] == "literal" and not p.get("diag_search_truncated") for p in rows)
    validate(args.output)
    report = {"status": "PASS", "job_id": job_id, "terminal_policy": "literal",
              "byte_identical": reference.read_bytes() == response.content, "semantic_match": True,
              "sha256": hashlib.sha256(response.content).hexdigest(), "variants": rows}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"API E2E PASS; byte-identical={report['byte_identical']}; SHA256={report['sha256']}", flush=True)


if __name__ == "__main__":
    main()
