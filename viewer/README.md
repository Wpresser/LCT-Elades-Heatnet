# LCT network viewer

Read-only React, TypeScript, Vite and MapLibre viewer for the final Java service artifacts.
The initial policy is **strict/literal**. The policy selector loads a separate GeoJSON and KPI set for the alternative interpretation, with disclosure text in the UI.

## Start

```powershell
cd viewer
npm install
npm run typecheck
npm run build
npm run dev
```

Open `http://localhost:5173/viewer/`.

The Spring technical UI remains at `http://localhost:8080/` when the backend is running. The viewer does not invent geometry: `public/data/strict.geojson` and `public/data/alternative.geojson` are copies of the final files under `results/`, while `public/data/input.geojson` is the corrected official dataset.

## Policy modes

- `Строгая трактовка` uses the global nearest boundary terminal ray and is the submission-safe default.
- `Альтернативная трактовка` uses the disclosed relaxed exit for the affected targets and reads a separate output. Its metrics are never combined with strict metrics.

The decision and source quotations are in `../docs/TERMINAL_POLICY_DECISION.md`.

## Viewer features

The interface preserves the dark MapLibre presentation view, 3D/top view, pan/zoom/rotate/pitch, layer panel, legend, engineering labels, DN/flow labels, source and chamber IDs, shared trunk highlighting, selected-object details, strict diagnostics, and optimized-vs-comparison mode.
