#!/usr/bin/env python3
"""Keep previously submitted direct presentation URLs usable after the Elades rename."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
PAIRS = [(ROOT / f"presentation/Elades_LCT2026_task2_presentation.{ext}",
          ROOT / f"presentation/LCT_Task2_final_presentation.{ext}") for ext in ("pdf", "pptx")]

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for source, alias in PAIRS:
        if not args.check:
            shutil.copyfile(source, alias)
        if not alias.exists() or alias.read_bytes() != source.read_bytes():
            raise SystemExit(f"Stale/missing presentation URL alias: {alias.name}")
        print(f"OK {alias.name} = {source.name}")
