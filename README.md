# LCT 2026 Task 2

Submission-ready Java 11 / Spring Boot 2.6.3 service for building new thermal-network routes from a GeoJSON FeatureCollection. The audited backend remains the canonical runtime. The `viewer/` directory contains the modern React/TypeScript/MapLibre read-only explorer.

## Run the backend

Prerequisites: Ubuntu 22, Docker Engine, `docker-compose` 1.29.2, and network access to Docker Hub/Maven Central.

```bash
docker-compose down
docker-compose up --build -d
docker-compose logs -f app
```

- Technical UI: `http://localhost:8080/`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- API docs: `http://localhost:8080/v3/api-docs`

Upload and download:

```bash
curl -F "file=@task/sources/Датасет скорректированный.geojson" http://localhost:8080/api/jobs
curl http://localhost:8080/api/jobs/<id>
curl -o result.geojson http://localhost:8080/api/jobs/<id>/result
```

The official corrected dataset SHA-256 is `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4be87f2a21914130`.

## Terminal policy

`HEATNET_TERMINAL_POLICY=literal` is the default and the submission-safe mode. Use `relaxed` only when the alternative interpretation is explicitly accepted. `strict` aliases `literal`; `any` aliases `relaxed`.

The policy decision, source quotations, strict and alternative metrics, and switching rationale are in `docs/TERMINAL_POLICY_DECISION.md` and `FINAL_METRICS.json`.

## Modern viewer

```bash
cd viewer
npm install
npm run typecheck
npm run build
npm run dev
```

Open `http://localhost:5173/viewer/`. The viewer defaults to strict and loads the separate `strict.geojson` or `alternative.geojson` artifact when the selector changes. It keeps the MapLibre 3D/top view, layer panel, engineering labels, source and chamber IDs, DN/flow labels, shared trunk highlight, selection details, and strict diagnostics.

## Verification

- Java tests: `cd service && ./mvnw test` (requires JDK 11).
- Viewer typecheck/build: `cd viewer && npm run typecheck && npm run build`.
- Strict audit: `python results/tools/lct_audit.py --input task/sources/Датасет скорректированный.geojson --output results/final_strict.geojson --terminal-policy strict`.
- Alternative audit: use `--output results/final_alternative.geojson --terminal-policy any`.
- Independent validator: `python validator/validator.py task/sources/Датасет скорректированный.geojson results/final_strict.geojson`.

Actual release results and any unavailable checks are recorded in `FINAL_VERIFICATION.md`. Unknown team identity fields remain in `docs/PRESENTATION_FIELDS_TO_FILL.md` and are not invented.
