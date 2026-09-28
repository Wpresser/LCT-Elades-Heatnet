# Independent audit: `final_strict.geojson`

- input: `Датасет скорректированный.geojson`
- terminal policy: `strict`
- ERROR: **0**, AMBIGUOUS: 0, WARNING: 6

## Variant v1

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 229867239.7813864 | 229867239.78155026 |
| chamber_construction_cost | 50000000 | 50000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 338995000 | 338995000.0 |
| calculated_cost | 568862239.7813864 | 568862239.7815503 |
| new_network_length | 1553.6517006113452 | 1553.6517006124884 |
| score | 20.589097815712854 | 20.589097815720873 |
| unconnected_oks_ids | [2, 5, 10] | [2, 5, 10] |
| coverage | | 14/17 |

segments 22 (special 0), new chambers 12, technical nodes 0, DN histogram {'100': 5, '125': 7, '150': 1, '200': 5, '250': 2, '300': 1, '65': 1}

## Variant v2

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 256423295.95944566 | 256423295.95960376 |
| chamber_construction_cost | 47000000 | 47000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 338995000 | 338995000.0 |
| calculated_cost | 595418295.9594457 | 595418295.9596038 |
| new_network_length | 1805.6816029830904 | 1805.6816029851282 |
| score | 22.08875709581375 | 22.08875709582429 |
| unconnected_oks_ids | [2, 5, 10] | [2, 5, 10] |
| coverage | | 14/17 |

segments 26 (special 1), new chambers 11, technical nodes 2, DN histogram {'100': 7, '125': 7, '150': 1, '200': 4, '250': 3, '300': 3, '65': 1}

## Findings

| level | code | variant | message |
|---|---|---|---|
| WARNING | ZIGZAG | v1 | heat_network "v1_net_11": 13.9 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v1 | heat_network "v1_net_11": 7.4 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_21": 13.9 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_21": 7.4 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_35": 23.1 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_35": 52.0 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
