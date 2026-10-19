# Independent audit: `scenario_osm_roads.geojson`

- input: `enriched.geojson`
- terminal policy: `exterior-fallback`
- ERROR: **0**, AMBIGUOUS: 0, WARNING: 6

## Variant v1

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 292945280.6904708 | 292945280.6901809 |
| chamber_construction_cost | 52000000 | 52000000.0 |
| existing_chamber_tie_in_count | 1 | 1 |
| existing_chamber_tie_in_cost | 5000000.0 | 5000000 |
| unconnected_penalty | 0.0 | 0 |
| calculated_cost | 292945280.6904708 | 292945280.6901809 |
| new_network_length | 2040.9882017095629 | 2040.9882017063728 |
| score | 14.325432464461873 | 14.325432464444184 |
| unconnected_oks_ids | [] | [] |
| coverage | | 17/17 |

segments 49 (special 10), new chambers 14, technical nodes 20, DN histogram {'100': 8, '125': 19, '150': 2, '200': 14, '250': 1, '300': 4, '65': 1}

## Variant v2

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 301814749.20633256 | 301814749.2060951 |
| chamber_construction_cost | 56000000 | 56000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 0.0 | 0 |
| calculated_cost | 301814749.20633256 | 301814749.2060951 |
| new_network_length | 2055.2187107349578 | 2055.2187107318605 |
| score | 14.616469109982184 | 14.616469109966243 |
| unconnected_oks_ids | [] | [] |
| coverage | | 17/17 |

segments 54 (special 12), new chambers 14, technical nodes 24, DN histogram {'100': 8, '125': 19, '150': 4, '200': 10, '250': 8, '300': 4, '65': 1}

## Findings

| level | code | variant | message |
|---|---|---|---|
| WARNING | ZIGZAG | v1 | heat_network "v1_net_6": 23.1 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v1 | heat_network "v1_net_6": 52.0 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v1 | heat_network "v1_net_73": 10.5 deg turn next to a 0.03 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_8": 23.1 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_8": 52.0 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_79": 10.5 deg turn next to a 0.03 m sub-segment (possible unjustified small break) |
| INFO | FINAL_EXTERIOR_FALLBACK | v1 | final straight section to target 17 uses another exterior boundary point: 14.1 m inside own OKS vs 5.4 m to the nearest exterior point (+8.8 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v1 | final straight section to target 10 uses another exterior boundary point: 9.7 m inside own OKS vs 6.5 m to the nearest exterior point (+3.1 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v1 | final straight section to target 2 uses another exterior boundary point: 9.1 m inside own OKS vs 5.8 m to the nearest exterior point (+3.3 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v2 | final straight section to target 10 uses another exterior boundary point: 9.7 m inside own OKS vs 6.5 m to the nearest exterior point (+3.1 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v2 | final straight section to target 2 uses another exterior boundary point: 9.1 m inside own OKS vs 5.8 m to the nearest exterior point (+3.3 m) (clarifications 29.09 No. 15) |
| INFO | FINAL_EXTERIOR_FALLBACK | v2 | final straight section to target 17 uses another exterior boundary point: 14.1 m inside own OKS vs 5.4 m to the nearest exterior point (+8.8 m) (clarifications 29.09 No. 15) |
