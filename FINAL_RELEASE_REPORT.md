# FINAL RELEASE REPORT

Дата: 2026-09-30. Статус: READY; известных critical release blockers нет.
Baseline HEAD: `5a5f6b0ef5f799a65fbfc9433e3fb9780f1c7dc2`.
Фактически проверенный production commit: `57bd009e28b9b12a65cb93fbfb5a79f285d50340`.
CI: [https://github.com/Wpresser/LCT-Elades-Heatnet/actions/runs/36634293102](https://github.com/Wpresser/LCT-Elades-Heatnet/actions/runs/36634293102) — completed/success. Code changes проверены до добавления
финальной документации/evidence; workflow повторяется на финальном commit и main.
Отчёт не содержит свой будущий self-referential commit SHA; `validated_commit` обозначает конкретный проверенный code commit.

## Исправления

- Viewer public/data включён в git; sync/check и SHA manifest связывают копии с authoritative файлами.
- Причина unconnected читается из summary без optional proof; тип камер — boolean diag_tie_in, включая false.
- Construction, penalty и calculated cost разделены; FINAL_METRICS генерируется и проверяется по двум summary.
- JDK 11 / LF executable Maven wrapper и exact dataset bytes проверены на clean checkout.
- MapLibre обновлён до 6.4.1 с исправлением XSS; Vite worker включён, подписи используют локальные шрифты.
  Обоснование: [официальный advisory](https://github.com/maplibre/maplibre-gl-js/security/advisories/GHSA-jrc7-96c5-q579),
  [официальная glyph specification](https://maplibre.org/maplibre-style-spec/glyphs/).
- Raw upload ограничен и Content-Length, и фактическими streamed bytes; неполные файлы удаляются при отказе.
- Повторяющийся typed node ID отклоняется; numeric/string IDs сохраняют различие. Формулы/геометрия solver не изменены.
- Independent validator сохраняет invalid-geometry target в unconnected penalty; dirty-input scenario теперь проверяется корректно.
- API restart-check metadata явно читается в UTF-8; Windows HTTP verify-existing + все validators повторены, exit 0.
- Contract checker возвращает exit 1 при ERROR; strict audit возвращает exit 1 также при AMBIGUOUS.
- CI fail-closed на Ubuntu: Java 11, Node 22, Python, Docker/PostgreSQL, реальный API output и restart persistence.
- Документация и исторические alternative check labels синхронизированы; missing optional assets не выдаются за обязательные.

## Реально выполненные команды и результаты

Команды ниже выполнялись из корня, кроме явно указанных рабочих директорий. Критические ошибки не игнорируются.

| Команда | Exit / результат |
|---|---|
| `git rev-parse HEAD` до изменений | 0, baseline выше |
| `service/mvnw.cmd -B test` с JAVA_HOME Temurin 11.0.32.1 | 0, 31 tests; failures/errors/skipped 0 |
| `cd service && ./mvnw -B test` на Ubuntu/JDK11 | 0, 31 tests; failures/errors/skipped 0 |
| `cd service && ./mvnw -B -DskipTests package` | 0 (Windows equivalent mvnw.cmd) |
| `cd viewer && npm ci` | 0; clean install, npm audit 0 vulnerabilities |
| `cd viewer && npm run test:policy` | 0, 7 tests passed |
| `cd viewer && npm run typecheck` | 0 |
| `cd viewer && npm run build` | 0; Vite chunk warning retained |
| `python scripts/sync_viewer_data.py --check` | 0, all three files + manifest match |
| `python scripts/check_release_metrics.py` | 0, both policies/cost components/ranks/hash/score match |
| `python -m pytest validator/test_validator.py scripts/test_release_checks.py -q` | 0, 24 passed locally; referenced CI predates last test, 23 passed |
| `python scenarios/check_scenarios.py service/target/scenarios` | 0, 15/15 scenarios |
| `docker compose config --quiet` | 0 on Ubuntu |
| `docker compose build` | 0, actual image build on Ubuntu |
| `docker compose up -d` | 0; db/app running |
| `timeout 120 bash -c 'until docker compose exec -T db pg_isready -U heatnet -d heatnet; do sleep 2; done'` | 0, PostgreSQL ready |
| `python scripts/api_e2e.py` | 0, root/OpenAPI/Swagger HTTP 200; upload/start/DONE/download PASS |
| `docker compose restart db` + readiness; `docker compose restart app` | 0; both restarted |
| `python scripts/api_e2e.py --verify-existing --output .release-check/api-after-restart.geojson` | 0, persisted job/output verified again |
| `python results/tools/inspect_zigzag.py` | 0, six individual deletion experiments; authoritative files unchanged |
| Browser clean clone/dev and production preview | PASS; screenshots and console evidence |
| `docker compose down` after CI | 0 |

Independent generated-output commands (all exit 0):

```bash
python results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output .release-check/api-result.geojson --terminal-policy strict --json .release-check/api-result.audit.json
python validator/validator.py "task/sources/Датасет скорректированный.geojson" .release-check/api-result.geojson --json .release-check/api-result.validator.json
python results/tools/output_contract_check.py "task/sources/Датасет скорректированный.geojson" .release-check/api-result.geojson
```

Также выполнены те же три проверки для saved strict; alternative audit запускался с `--terminal-policy any`
(его exit 0 не означает буквальное соответствие §2.2). Ошибочный CLI option `alternative` на локальной попытке
дал exit 2; исправленный `any` запуск завершился 0. Одна промежуточная CI попытка `36633910957` провалилась
из-за испорченной кодировки dataset path; UTF-8 восстановлен, последующий run выше — success.
Регрессионная проверка now проверяет dataset path и fail-closed workflow.

## Docker / API evidence

Локально Docker/WSL отсутствует; Docker PASS получен реальным запуском на Ubuntu 22.04 GitHub runner,
а не из документации или проверки yaml. Java11 тесты выполнены и локально, и на runner.
PostgreSQL16 используется production Spring postgres profile; Hikari connection успешно открыт.
API загружает официальный файл 633402 bytes, получает id, явно запускает job, ждёт DONE и скачивает output.
Оба ranked variants и полный GeoJSON совпали побайтно с strict artifact. После restart db/app скачивание
и все независимые проверки повторены успешно. Full CI logs/Surefire/scenarios/API output доступны в artifact
`release-evidence`; компактные portable summaries — `results/checks/release/`.

## Метрики и hashes

| Показатель | Strict/literal v1, default | Alternative/relaxed v1, disclosed |
|---|---:|---:|
| Coverage | 14/17 | 17/17 |
| Unconnected IDs | [2, 5, 10] | [] |
| Construction cost, ₽ | 229867239.7813864 | 271516096.5294552 |
| Unconnected penalty, ₽ | 338995000 | 0.0 |
| Calculated cost, ₽ | 568862239.7813864 | 271516096.5294552 |
| New length, m | 1553.6517006113452 | 1824.6731652331825 |
| Score | 20.589097815712854 | 13.07647019852429 |

SHA-256:

- `results/final_strict.geojson`: `f2dbbf86f1d669a8dd30b44d3de63c573bfe9c1bfbb43b02e118998535b17070`.
- Real API output: `f2dbbf86f1d669a8dd30b44d3de63c573bfe9c1bfbb43b02e118998535b17070` — byte-identical, including variant ranks and literal policy.
- `results/final_alternative.geojson`: `150743451942d7ed895158905de2da815c04c2eb95339f49da5ef3a8cd1b0d80`.
- Official dataset: `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`.

Strict/alternative hashes unchanged from baseline. Dataset Git blob unchanged; baseline Windows autocrlf checkout
had hash `b40cc1170640c92ae207770e7afe299d9795ef5bf18ef75199b6912b06aeaef6`.
`.gitattributes` now preserves official GeoJSON bytes on Windows and Linux. Earlier documentation also contained
a missing `fe` in dataset SHA; corrected to the actual Git blob hash above.

## Independent validation and generality

Strict: audit ERROR 0, AMBIGUOUS 0, WARNING 6; Python validator ERROR 0; contract ERROR 0 (4 informational notes).
Checks cover topology/connectivity/cycles/endpoint references, typed IDs/finite values, downstream flow/DN,
continuous same-DN paths, chamber degree and cost, pipes/penalty/score, forbidden intersections/special crossings/final approach.
Alternative: ERROR 0, AMBIGUOUS 6, WARNING 7; independently disclosed. Official clarification absent.

All 15 scenarios passed: cable_crossing, city_dense, cross_existing_network, dirty_input, gas_crossing, no_network,
park_between, pipe_under_road, road_crossing, road_with_gas, social_and_prohibited, string_ids_two_in_one,
target_outside_building, tram_crossing, unreachable_island. No new solver exceptions for competition IDs/coordinates.
Search matches `== 2` in routing refer to graph degree/dimension, not target IDs. Synthetic test fixtures retain
ordinary sample coordinates; input/output authoritative dataset is not embedded in routing code.

## Viewer clean clone

Fresh remote clone, npm ci, tests/typecheck/build exit 0; npm run dev ready. Browser checked strict default
14/17/S20.589/calculated568.9M/1.554km; separate alternative17/17/S13.076; missing-proof reason from summary;
DN200/flow76.27 card; engineering labels; 3D/top view; layer toggles/shared trunks. No console errors/warnings
on fresh final code, required GeoJSON files all served, no glyph demo-server requests.
Screenshots: `results/checks/release/viewer-strict-unconnected.jpg`, `viewer-alternative-3d.jpg`, `viewer-engineering.jpg`.
Production preview also rendered the actual map. Clean-clone evidence includes the exact tested viewer commit.

## Security / robustness / cleanup

Targeted actual Java tests: duplicate typed node IDs fail; string/numeric IDs remain distinct; nonfinite flow/geometry
preserve finite unconnected representation; Content-Length and streamed/chunked raw limits return 413;
partial upload cleanup; queue overflow is terminal FAILED; jobs interrupted before restart become FAILED/restartable.
User-supplied filename never selects storage path (UUID job directories). Unknown job paths return 404;
input/result access requires a stored job rather than an arbitrary filesystem pathname. Malformed JSON/geometry
and dirty/missing data handled by InputLoader tests and synthetic scenarios. DB/application restart persistence
verified through production HTTP. This is a scoped release audit, not a claim of exhaustive security certification.

No tracked node_modules/dist/target/caches/.env, local absolute user paths, tokens, private keys or handoff prompts.
Compose `heatnet` defaults are intentional local demonstration credentials; DB port is not published.
PDF/PPTX: all 12 slides/pages text inspected for technical placeholders, first/last PDF pages rendered and reviewed;
no placeholders found, participants' intentional personal information unchanged. Historical 3GB/50-client figures
in existing presentation were not re-benchmarked and are not current release PASS evidence.
Independent read-only review: no critical/important introduced findings; artifacts and routing code unchanged.

## Remaining warnings and blocker decision

No known release blockers.

- Six strict ZIGZAG findings were each inspected by deleting the indicated vertex in a temporary output and
  recomputing cost/length/score, then running both independent validators. Details: `results/checks/zigzag_review.json`.
- `v2_net_35`, vertex 7: deletion gives audit FINAL_NOT_NEAREST and validator oks.final_approach.
  This point is necessary for the literal terminal direction and remains.
- Other five individual deletions pass both validators, saving only 0.005–0.052 m each. They are optional geometric
  changes; combined safety and backend reproducibility are not established. They are deliberately retained
  under the user's stronger champion-freeze/no-cosmetic-solver-change constraint. They are not falsely called
  mathematically necessary. No global smoothing or manual authoritative GeoJSON patch was applied.
- Python validator gives 2 repeated special.set_change warnings on v2_net_39 (secondary variant only), ERROR 0.
- Vite warns main chunk >500kB (about1.194MB raw /326kB gzip); production build and map work.
- Alternative's 6 AMBIGUOUS terminal findings and 7 warnings remain disclosed; alternative contract has a microsegment warning.
- General solver boundaries remain in docs/ALGORITHM.md; global optimality and legacy workload benchmarks are not newly claimed.

## Files changed

- `.gitattributes`
- `.github/workflows/release-check.yml`
- `.gitignore`
- `ARCHIVE_CONTENTS.txt`
- `ARCHIVE_SCOPE.md`
- `FINAL_BLOCKERS.md`
- `FINAL_METRICS.json`
- `FINAL_RELEASE_REPORT.md`
- `FINAL_RELEASE_STATUS.json`
- `FINAL_VERIFICATION.md`
- `README.md`
- `docker-compose.yml`
- `docs/ALGORITHM.md`
- `docs/DEMO.md`
- `docs/TERMINAL_POLICY_DECISION.md`
- `results/README.md`
- `results/checks/output_contract_check.txt`
- `results/checks/release/alternative-audit.json`
- `results/checks/release/alternative-contract.txt`
- `results/checks/release/alternative-validator.txt`
- `results/checks/release/api-after-restart.audit.json`
- `results/checks/release/api-after-restart.validator.json`
- `results/checks/release/api-e2e.json`
- `results/checks/release/api-result.audit.json`
- `results/checks/release/api-result.validator.json`
- `results/checks/release/ci-run.json`
- `results/checks/release/ci-strict-audit.json`
- `results/checks/release/java-tests.json`
- `results/checks/release/metric-search.json`
- `results/checks/release/strict-audit.json`
- `results/checks/release/strict-contract.txt`
- `results/checks/release/strict-validator.txt`
- `results/checks/release/viewer-alternative-3d.jpg`
- `results/checks/release/viewer-clean-clone.json`
- `results/checks/release/viewer-engineering.jpg`
- `results/checks/release/viewer-strict-unconnected.jpg`
- `results/checks/validator_result_2d_competition.txt`
- `results/checks/zigzag_review.json`
- `results/tools/inspect_zigzag.py`
- `results/tools/lct_audit.py`
- `results/tools/output_contract_check.py`
- `scripts/api_e2e.py`
- `scripts/check_release_metrics.py`
- `scripts/requirements.txt`
- `scripts/sync_viewer_data.py`
- `scripts/test_release_checks.py`
- `service/mvnw`
- `service/src/main/java/ru/lct/heatnet/api/JobController.java`
- `service/src/main/java/ru/lct/heatnet/io/InputLoader.java`
- `service/src/main/java/ru/lct/heatnet/jobs/JobService.java`
- `service/src/test/java/ru/lct/heatnet/InputLoaderTest.java`
- `service/src/test/java/ru/lct/heatnet/JobRecoveryTest.java`
- `service/src/test/java/ru/lct/heatnet/RawUploadLimitTest.java`
- `validator/test_validator.py`
- `validator/validator.py`
- `viewer/README.md`
- `viewer/package-lock.json`
- `viewer/package.json`
- `viewer/public/data/alternative.geojson`
- `viewer/public/data/input.geojson`
- `viewer/public/data/manifest.json`
- `viewer/public/data/strict.geojson`
- `viewer/src/data.ts`
- `viewer/src/main.tsx`
- `viewer/test/data.test.mjs`
