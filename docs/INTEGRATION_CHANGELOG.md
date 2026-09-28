# Integration changelog

- Used the audited Opus Java/Spring repository as the release base.
- Added the Sol React/TypeScript/MapLibre viewer under `viewer/` and served its factual final artifacts from `viewer/public/data/`.
- Added a strict/alternative policy selector with separate GeoJSON and KPI summaries.
- Made strict/literal the default in `docker-compose.yml`, `TerminalPolicy.java`, tests, README, and presentation KPI slides.
- Kept v2 as a comparison summary while the map stays on v1 so labels and selected-object metadata cannot mix variants.
- Retained the technical fallback UI under `service/src/main/resources/static/technical/`.
- Added final metric, verification, blocker, presentation, and removal documentation.
