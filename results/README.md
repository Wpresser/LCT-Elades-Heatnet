# Финальные результаты

Результаты получены сервисом на исправленном конкурсном наборе `task/sources/Датасет скорректированный.geojson` (SHA-256 `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`): тест `SolverDatasetTest` и реальный API (`scripts/api_e2e.py`, jar и Docker) дают побайтно одинаковые выгрузки.

| Файл | Режим §2.2 | v1 | v2 | SHA-256 |
|---|---|---|---|---|
| `final_main.geojson` | основной, `relaxed` (доп. разъяснения 29.09, №15) | 17/17, C 273,562 млн ₽, L 1 845,7 м, S 13,1969 | 17/17, C 289,663 млн ₽, L 2 078,3 м, S 14,3455 | `bd5ce35f9c5df8980959a2426f845b2e0ad752753dfe1f62618ccec1db984079` |
| `final_strict.geojson` | строгое прочтение, `literal`, для сравнения | 14/17, C 568,864 млн ₽, L 1 553,7 м, S 20,5892 | 14/17, C 608,079 млн ₽, L 1 906,2 м, S 22,7448 | `e670942c8879748df86544b5d05b606b438332fd5a8f716cfb803454dab4592d` |
| `scenario_osm_roads.geojson` | основной, на наборе с дорогами OSM (`scenarios/osm_roads/enriched.geojson`) | 17/17, C 292,945 млн ₽, L 2 041,0 м, S 14,3254 | 17/17, C 301,815 млн ₽, L 2 055,2 м, S 14,6165 | `4c2845f8e3d7f766acfc4b709bd88a56d9fdf17d03a7f28dc207baa2c92a35c3` |

Основной результат — `final_main.geojson`. Метрики двух режимов не смешиваются.

Проверка из корня репозитория:

```bash
python3 results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output results/final_main.geojson --terminal-policy exterior-fallback
python3 validator/validator.py "task/sources/Датасет скорректированный.geojson" results/final_main.geojson
python3 results/tools/output_contract_check.py "task/sources/Датасет скорректированный.geojson" results/final_main.geojson
python3 results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output results/final_strict.geojson --terminal-policy strict
```

Зависимости — `pip install -r scripts/requirements.txt` (shapely, pyproj); для карт — ещё `matplotlib`.

Здесь C обозначает `calculated_cost`, то есть строительство плюс штраф. У строгого v1 из 571,594 млн ₽
штраф за три неподключённые точки — 338,995 млн ₽. У основного v1 штрафа нет.
Все компоненты и варианты по рангу — в `../FINAL_METRICS.json`.

`checks/final_main_audit.*`, `checks/validator_result_2d_competition.txt` и `checks/output_contract_check.txt` —
проверки основного результата. `checks/final_strict_audit.*` — строгого. `checks/osm_roads_*` — набора с дорогами.
`checks/geometry/` — метрики геометрии и карта «сдача 30.09 / сейчас», `checks/BENCHMARK_BEFORE_AFTER.csv` — замеры
скорости с описанием стенда. `checks/existing_network_added_flow.md` — справочно, добавленный расход по существующей
сети. `checks/release/` и `checks/zigzag_review.json` — доказательства сдачи 30.09 (commit 08ee21f), сохранены без
изменений. Реальный API воспроизводится `../scripts/api_e2e.py`.
