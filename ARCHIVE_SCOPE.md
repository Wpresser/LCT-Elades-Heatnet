# Состав submission repository

Включены Java 11 service, viewer, официальный dataset и документы задания, strict/alternative результаты,
независимые validators, synthetic scenarios, презентация, release CI, скрипты воспроизводимости и audit evidence.
Strict/literal — default submission; alternative/relaxed раскрыт отдельно.

`viewer/public/data` содержит проверяемые byte-identical копии из `results` и `task/sources`, а manifest — их hashes.
Эти копии не редактируются независимо: используйте `scripts/sync_viewer_data.py` и CI `--check`.
Список файлов — `ARCHIVE_CONTENTS.txt`; метрики — `FINAL_METRICS.json`; текущая проверка —
`FINAL_RELEASE_REPORT.md` и `FINAL_RELEASE_STATUS.json`.

Исключены node_modules, dist, target, Python caches, local environments, .env и локальные runtime/log directories.
CI upload ограничен `.release-check`, Surefire и scenario outputs; он не включает credentials или .env.
Персональные данные намеренно заполненной конкурсной презентации сохранены.
