# Independent audit: `final_alternative.geojson`

- input: `Датасет скорректированный.geojson`
- terminal policy: `any`
- ERROR: **0**, AMBIGUOUS: 6, WARNING: 7

## Variant v1

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 271516096.5294552 | 271516096.529582 |
| chamber_construction_cost | 60000000 | 60000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 0.0 | 0 |
| calculated_cost | 271516096.5294552 | 271516096.529582 |
| new_network_length | 1824.6731652331825 | 1824.6731652339085 |
| score | 13.07647019852429 | 13.076470198530021 |
| unconnected_oks_ids | [] | [] |
| coverage | | 17/17 |

segments 27 (special 0), new chambers 14, technical nodes 0, DN histogram {'100': 6, '125': 9, '150': 1, '200': 5, '250': 3, '300': 2, '65': 1}

## Variant v2

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 289678319.56243277 | 289678319.5623857 |
| chamber_construction_cost | 57000000 | 57000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 0.0 | 0 |
| calculated_cost | 289678319.56243277 | 289678319.5623857 |
| new_network_length | 2078.298335062663 | 2078.2983350627164 |
| score | 14.345887952936106 | 14.345887952934946 |
| unconnected_oks_ids | [] | [] |
| coverage | | 17/17 |

segments 33 (special 1), new chambers 15, technical nodes 2, DN histogram {'100': 8, '125': 9, '150': 3, '200': 7, '250': 2, '300': 3, '65': 1}

## Findings

| level | code | variant | message |
|---|---|---|---|
| AMBIGUOUS | FINAL_NOT_STRICT_NEAREST | v1 | final straight section to target 2 passes the nearest boundary only under the relaxed policy 'any', not under the literal global-nearest reading (§2.2) |
| AMBIGUOUS | FINAL_NOT_STRICT_NEAREST | v1 | final straight section to target 5 passes the nearest boundary only under the relaxed policy 'any', not under the literal global-nearest reading (§2.2) |
| AMBIGUOUS | FINAL_NOT_STRICT_NEAREST | v1 | final straight section to target 10 passes the nearest boundary only under the relaxed policy 'any', not under the literal global-nearest reading (§2.2) |
| AMBIGUOUS | FINAL_NOT_STRICT_NEAREST | v2 | final straight section to target 10 passes the nearest boundary only under the relaxed policy 'any', not under the literal global-nearest reading (§2.2) |
| AMBIGUOUS | FINAL_NOT_STRICT_NEAREST | v2 | final straight section to target 5 passes the nearest boundary only under the relaxed policy 'any', not under the literal global-nearest reading (§2.2) |
| AMBIGUOUS | FINAL_NOT_STRICT_NEAREST | v2 | final straight section to target 2 passes the nearest boundary only under the relaxed policy 'any', not under the literal global-nearest reading (§2.2) |
| WARNING | ZIGZAG | v1 | heat_network "v1_net_14": 13.9 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v1 | heat_network "v1_net_14": 7.4 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_30": 13.9 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_30": 7.4 deg turn next to a 0.87 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_46": 23.1 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_46": 52.0 deg turn next to a 0.82 m sub-segment (possible unjustified small break) |
| WARNING | ZIGZAG | v2 | heat_network "v2_net_55": 10.5 deg turn next to a 0.03 m sub-segment (possible unjustified small break) |
