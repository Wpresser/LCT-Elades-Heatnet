> **История: сдача 30.09.2026 (commit `08ee21f`), режим по умолчанию тогда — `literal` (14/17).** Документ сохранён как доказательство той сдачи и после неё не обновлялся. Актуальная версия — доработка по обратной связи экспертов: что сделано и какие проверки выполнены — [docs/EXPERT_FEEDBACK.md](docs/EXPERT_FEEDBACK.md), метрики — [FINAL_METRICS.json](FINAL_METRICS.json).

# Release blockers

No known release blockers.

Java 11 tests, Ubuntu Docker build, PostgreSQL/backend startup, real official-dataset API E2E,
restart persistence and independent generated-output validation фактически завершились успешно.
Viewer проверен после clean clone, npm ci, тестов, typecheck, build и dev запуска в browser.
Evidence: `FINAL_RELEASE_REPORT.md`, `FINAL_RELEASE_STATUS.json`, `results/checks/release/`.

Оставшиеся нефатальные ограничения: 6 strict ZIGZAG warnings, 2 предупреждения Python validator
на одном special segment варианта v2, размер frontend chunk. Они раскрыты в release report.
Alternative/relaxed имеет 6 AMBIGUOUS по §2.2 и не является default submission.
Официального нового разъяснения организаторов нет. Глобальная оптимальность не гарантируется.
