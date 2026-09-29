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
    (tmp_path / "viewer/public/data/strict.geojson").write_text("stale")
    with pytest.raises(ValueError, match="strict.geojson"):
        sync.sync(tmp_path, check=True)


def test_contract_errors_have_nonzero_exit(tmp_path):
    doc = json.loads((ROOT / "results/final_strict.geojson").read_text(encoding="utf-8"))
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
    assert jobs["release"]["env"]["HEATNET_TERMINAL_POLICY"] == "literal"
    assert not any(step.get("continue-on-error") or "|| true" in step.get("run", "") for step in steps)
    upload = next(step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@"))
    assert upload["with"]["include-hidden-files"] is True
    assert upload["with"]["if-no-files-found"] == "error"
