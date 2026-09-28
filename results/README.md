# Final result artifacts

The two release outputs were calculated against the corrected official dataset (`task/sources/Датасет скорректированный.geojson`, SHA-256 `cffb7133419d93fe364a53015a7d3ead289f671cbfaf6f4be87f2a21914130`).

| file | policy | v1 | v2 | SHA-256 |
|---|---|---|---|---|
| `final_strict.geojson` | literal / strict | 14/17, C 568.862M RUB, L 1553.652 m, S 20.5891 | 14/17, C 595.418M RUB, L 1805.682 m, S 22.0888 | `f2dbbf86f1d669a8dd30b44d3de63c573bfe9c1bfbb43b02e118998535b17070` |
| `final_alternative.geojson` | relaxed / alternative | 17/17, C 271.516M RUB, L 1824.673 m, S 13.0765 | 17/17, C 289.678M RUB, L 2078.298 m, S 14.3459 | `150743451942d7ed895158905de2da815c04c2eb95339f49da5ef3a8cd1b0d80` |

The strict file is the submission default. The alternative file is retained for the disclosed §2.2 interpretation and is never combined with strict metrics.

Run the independent checks from `tools/`:

```bash
python tools/lct_audit.py --input ../task/sources/Датасет скорректированный.geojson --output final_strict.geojson --terminal-policy strict
python tools/lct_audit.py --input ../task/sources/Датасет скорректированный.geojson --output final_alternative.geojson --terminal-policy any
python tools/output_contract_check.py --input ../task/sources/Датасет скорректированный.geojson --output final_strict.geojson
```
