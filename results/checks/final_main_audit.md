# Independent audit: `final_main.geojson`

- input: `Датасет скорректированный.geojson`
- terminal policy: `exterior-fallback`
- ERROR: **0**, AMBIGUOUS: 0, WARNING: 3

## Variant v1

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 273561967.97212696 | 273561967.9719292 |
| chamber_construction_cost | 60000000 | 60000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 0.0 | 0 |
| calculated_cost | 273561967.97212696 | 273561967.9719292 |
| new_network_length | 1845.7049970776632 | 1845.7049970758474 |
| score | 13.196850094452543 | 13.196850094441558 |
| unconnected_oks_ids | [] | [] |
| coverage | | 17/17 |

segments 27 (special 0), new chambers 14, technical nodes 0, DN histogram {'100': 6, '125': 9, '150': 1, '200': 5, '250': 3, '300': 2, '65': 1}

## Variant v2

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 289662746.22315645 | 289662746.2232991 |
| chamber_construction_cost | 57000000 | 57000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 0.0 | 0 |
| calculated_cost | 289662746.22315645 | 289662746.2232991 |
| new_network_length | 2078.3122040839266 | 2078.3122040851017 |
| score | 14.345493506500162 | 14.34549350650768 |
| unconnected_oks_ids | [] | [] |
| coverage | | 17/17 |

segments 33 (special 1), new chambers 15, technical nodes 2, DN histogram {'100': 8, '125': 9, '150': 3, '200': 7, '250': 2, '300': 3, '65': 1}

## Findings

| level | code | variant | message |
|---|---|---|---|
| WARNING | ZIGZAG | v2 | heat_network "v2_net_46": 23.1 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_46": 52.0 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_55": 10.5 deg turn next to a 0.03 m sub-segment (possible unjustified small break) |
| INFO | FINAL_EXTERIOR_FALLBACK | v1 | final straight section to target 2 uses another exterior boundary point: 8.8 m inside own OKS vs 5.8 m to the nearest exterior point (+3.0 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v1 | final straight section to target 10 uses another exterior boundary point: 8.8 m inside own OKS vs 6.5 m to the nearest exterior point (+2.3 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v2 | final straight section to target 10 uses another exterior boundary point: 9.7 m inside own OKS vs 6.5 m to the nearest exterior point (+3.1 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v2 | final straight section to target 2 uses another exterior boundary point: 9.1 m inside own OKS vs 5.8 m to the nearest exterior point (+3.3 m) (clarifications 29.09 No. 15) |
