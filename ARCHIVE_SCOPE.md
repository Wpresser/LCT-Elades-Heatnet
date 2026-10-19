# Состав submission repository

Включены Java 11 service, viewer, официальный dataset и документы задания, основной и строгий результаты,
независимые validators, synthetic scenarios, презентация, release CI, скрипты воспроизводимости и audit evidence.
Основной результат — `results/final_main.geojson` (relaxed, разъяснения 29.09 №15); строгое прочтение §2.2 —
`results/final_strict.geojson`, для сравнения.

`viewer/public/data` содержит проверяемые byte-identical копии из `results` и `task/sources`, а manifest — их hashes.
Эти копии не редактируются независимо: используйте `scripts/sync_viewer_data.py` и CI `--check`.
Метрики — `FINAL_METRICS.json`; доработка после обратной связи экспертов и её проверки — `docs/EXPERT_FEEDBACK.md`.
`ARCHIVE_CONTENTS.txt`, `FINAL_RELEASE_REPORT.md`, `FINAL_RELEASE_STATUS.json`, `FINAL_VERIFICATION.md` — история сдачи 30.09.

Исключены node_modules, dist, target, Python caches, local environments, .env и локальные runtime/log directories.
CI upload ограничен `.release-check`, Surefire и scenario outputs; он не включает credentials или .env.
Персональные данные намеренно заполненной конкурсной презентации сохранены.
