# Итоговая матрица проверки

Подтверждённый production commit: `57bd009e28b9b12a65cb93fbfb5a79f285d50340`. [Ubuntu CI run](https://github.com/Wpresser/LCT-Elades-Heatnet/actions/runs/36634293102) завершился `success`.
Документация и дополнительные регрессионные проверки выпуска добавлены после этой проверки;
workflow повторяется на итоговой ветке и main. Точный provenance — `FINAL_RELEASE_REPORT.md`.

| Проверка | Фактический результат | Evidence |
|---|---|---|
| JDK 11 Maven tests | PASS, 31 tests, 0 failures/errors/skipped | CI + local Temurin 11.0.32.1 |
| Viewer policy/data tests | PASS, 7/7 | npm run test:policy |
| TypeScript / production build | PASS / PASS | local clean clone + CI |
| npm audit | PASS, 0 vulnerabilities | clean clone + CI |
| Viewer data / metrics consistency | PASS | sync --check; check_release_metrics.py |
| Python unit/regression tests | PASS, 24/24 locally; 23/23 on referenced CI | validator + release scripts |
| Synthetic scenarios | PASS, 15/15 | ScenarioTest + check_scenarios.py |
| Compose config / image build | PASS / PASS, exit 0 | Ubuntu CI |
| Backend / PostgreSQL startup | PASS / PASS | HTTP readiness + pg_isready |
| Root / OpenAPI / Swagger | PASS, HTTP 200 | api_e2e.py |
| API upload / start / DONE / download | PASS | official dataset; real generated output |
| DB/application restart persistence | PASS | re-download completed job after both restarts |
| API output vs release artifact | PASS, byte-identical SHA-256 | api-e2e.json |
| Strict independent audit | PASS: ERROR 0, AMBIGUOUS 0, WARNING 6 | saved + API generated output |
| Strict Python validator | PASS: ERROR 0; v1 warnings 0, v2 warnings 2 | API validator JSON |
| Strict output contract | PASS: ERROR 0, 4 notes | CI E2E + strict-contract.txt |
| Alternative independent audit | ERROR 0, AMBIGUOUS 6, WARNING 7 | disclosed relaxed policy only |
| Clean-clone browser | PASS: strict default; alternative; reason without proof; DN/flow; labels; 3D/top/layers; console errors/warnings 0 | screenshots + viewer-clean-clone.json |
| Input robustness | PASS, targeted tests for streamed limit, duplicate typed IDs, nonfinite input, queue overflow, recovery | Java suite + scenarios |
| Old metric / hardcoded exception search | PASS: old listed scores absent, no dataset-specific solver branches | metric-search.json + source search |
| PDF/PPTX placeholders | PASS: 12 slides/pages text inspected, no technical placeholders | report |
| Independent code review | No critical/important introduced findings | report |

Literal remains default; strict and alternative artifacts are unchanged from baseline.
Exact commands, hashes, warning decisions and changed files: `FINAL_RELEASE_REPORT.md`.
