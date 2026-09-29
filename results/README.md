# Финальные результаты

Оба результата получены на исправленном конкурсном наборе `task/sources/Датасет скорректированный.geojson` (SHA-256 `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4befe87f2a21914130`).

| Файл | Трактовка | v1 | v2 | SHA-256 |
|---|---|---|---|---|
| `final_strict.geojson` | строгая, по умолчанию | 14/17, C 568,862 млн ₽, L 1 553,652 м, S 20,5891 | 14/17, C 595,418 млн ₽, L 1 805,682 м, S 22,0888 | `f2dbbf86f1d669a8dd30b44d3de63c573bfe9c1bfbb43b02e118998535b17070` |
| `final_alternative.geojson` | альтернативная, с раскрытым допущением | 17/17, C 271,516 млн ₽, L 1 824,673 м, S 13,0765 | 17/17, C 289,678 млн ₽, L 2 078,298 м, S 14,3459 | `150743451942d7ed895158905de2da815c04c2eb95339f49da5ef3a8cd1b0d80` |

Для сдачи по умолчанию выбран strict. Альтернативная геометрия и её метрики не смешиваются со строгими.

Проверка из корня репозитория:

```bash
python results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output results/final_strict.geojson --terminal-policy strict
python results/tools/lct_audit.py --input "task/sources/Датасет скорректированный.geojson" --output results/final_alternative.geojson --terminal-policy any
python results/tools/output_contract_check.py "task/sources/Датасет скорректированный.geojson" results/final_strict.geojson
```

Здесь C обозначает `calculated_cost`, то есть строительство плюс штраф. Strict v1:
`construction_cost = 229867239.7813864`, `unconnected_penalty = 338995000`,
`calculated_cost = 568862239.7813864`. Все компоненты и ranked variants — в `../FINAL_METRICS.json`.

Актуальные отдельные проверки strict/alternative и browser evidence — `checks/release/`.
`checks/validator_result_2d_competition.txt` и `checks/output_contract_check.txt` — ранее сохранённые
проверки alternative, не доказательство strict. Реальное production API воспроизводится `../scripts/api_e2e.py`.
