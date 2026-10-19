import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("sync_viewer_data", ROOT / "scripts/sync_viewer_data.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def test_missing_and_stale_viewer_data_fail(tmp_path):
    for source in sync.SOURCES.values():
        p = tmp_path / source
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text('{"type":"FeatureCollection","features":[]}', encoding="utf-8")
    with pytest.raises(ValueError, match="missing/stale"):
        sync.sync(tmp_path, check=True)
    sync.sync(tmp_path)
    sync.sync(tmp_path, check=True)
    (tmp_path / "viewer/public/data/main.geojson").write_text("stale")
    with pytest.raises(ValueError, match="main.geojson"):
        sync.sync(tmp_path, check=True)


def test_contract_errors_have_nonzero_exit(tmp_path):
    doc = json.loads((ROOT / "results/final_main.geojson").read_text(encoding="utf-8"))
    next(f["properties"] for f in doc["features"] if f["properties"]["object_type"] == "variant_summary")["score"] = 0
    invalid = tmp_path / "bad.geojson"
    invalid.write_text(json.dumps(doc), encoding="utf-8")
    result = subprocess.run([sys.executable, str(ROOT / "results/tools/output_contract_check.py"),
                             str(ROOT / sync.SOURCES["input.geojson"]), str(invalid)], capture_output=True)
    assert b"ERROR" in result.stdout
    assert result.returncode == 1


def test_ci_preserves_authoritative_dataset_path_and_fails_closed():
    import yaml
    workflow = (ROOT / ".github/workflows/release-check.yml").read_text(encoding="utf-8")
    assert Path(sync.SOURCES["input.geojson"]).name in workflow
    jobs = yaml.safe_load(workflow)["jobs"]
    steps = jobs["release"]["steps"]
    assert jobs["release"]["env"]["HEATNET_TERMINAL_POLICY"] == "relaxed"
    assert not any(step.get("continue-on-error") or "|| true" in step.get("run", "") for step in steps)
    upload = next(step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@"))
    assert upload["with"]["include-hidden-files"] is True
    assert upload["with"]["if-no-files-found"] == "error"


def _audit_module():
    spec = importlib.util.spec_from_file_location("lct_audit", ROOT / "results/tools/lct_audit.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_exterior_fallback_policy_rejects_courtyard_entry():
    """Clarifications 29.09 No. 15: the final section must leave through the exterior boundary."""
    from shapely.geometry import LineString, Point, Polygon
    audit = _audit_module()
    og = Polygon([(0, 0), (40, 0), (40, 40), (0, 40)], [[(10, 10), (30, 10), (30, 30), (10, 30)]])
    target = {"id": 5, "pt": Point(7, 20)}  # 3 m to the courtyard wall, 7 m to the exterior

    def run(*xy):
        final = LineString(xy)
        info = {"exterior": any(final.distance(b) <= audit.NODE_TOL
                                for b in audit.nearest_boundary_points(target["pt"], og, "exterior"))}
        rep = audit.Report()
        audit.audit_exterior_fallback(target, og, final, final.intersection(og), info, rep, "v1")
        return [(i["level"], i["code"]) for i in rep.items]

    assert run((7, 20), (10, 20), (20, 20)) == [("ERROR", "FINAL_NOT_EXTERIOR")]
    assert run((7, 20), (0, 20), (-10, 20)) == []
    assert run((7, 20), (0, 25), (-10, 30)) == [("INFO", "FINAL_EXTERIOR_FALLBACK")]
    assert run((7, 20), (7, 0), (7, -10)) == [("INFO", "FINAL_EXTERIOR_FALLBACK"), ("WARNING", "FINAL_LONG_INSIDE")]


def _validator_fixtures():
    sys.path.insert(0, str(ROOT / "validator"))
    spec = importlib.util.spec_from_file_location("test_validator_fixtures", ROOT / "validator/test_validator.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_audit_two_metre_crossing_rule(tmp_path):
    """Clarifications 29.09 No. 14: special passage over a gas line is exactly ±2 m along the new axis."""
    import copy
    audit, T = _audit_module(), _validator_fixtures()
    inp = copy.deepcopy(T.INPUT_A)
    inp["features"].append(T.feat({"id": 50, "object_type": "restriction", "restriction_type": "gas_pipeline"},
                                  T.line((-100, 50), (100, 50))))

    def run(lo, hi):
        special = (hi - lo) * 89_748 * 1.25
        out = T.fc([
            T.feat({"id": "v1_tn_1", "object_type": "technical_node", "variant_id": "v1"}, T.pt(0, lo)),
            T.feat({"id": "v1_tn_2", "object_type": "technical_node", "variant_id": "v1"}, T.pt(0, hi)),
            T.net("v1_net_1", "input_chamber_1", "v1_tn_1", 20.0, 100, lo, lo * 89_748, (0, 0), (0, lo)),
            T.net("v1_net_2", "v1_tn_1", "v1_tn_2", 20.0, 100, hi - lo, special, (0, lo), (0, hi), laying="special"),
            T.net("v1_net_3", "v1_tn_2", "input_oks_1", 20.0, 100, 100 - hi, (100 - hi) * 89_748, (0, hi), (0, 100)),
            T.summary("v1", 1, 100 * 89_748 + (hi - lo) * 89_748 * 0.25 + 5_000_000, 0, 1, 0, 100.0, 0.0, []),
        ])
        i, o = tmp_path / "in.geojson", tmp_path / "out.geojson"
        i.write_text(json.dumps(inp), encoding="utf-8")
        o.write_text(json.dumps(out), encoding="utf-8")
        return {it["code"] for it in audit.run(i, o, "exterior-fallback")["items"] if it["level"] == "ERROR"}

    assert not {"CLEARANCE", "SPECIAL_BEYOND_2M", "SPECIAL_ZONE"} & run(48.0, 52.0)
    assert "SPECIAL_BEYOND_2M" in run(47.5, 52.5)
