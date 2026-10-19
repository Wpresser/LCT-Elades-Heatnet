# Independent audit: `final_strict.geojson`

- input: `Датасет скорректированный.geojson`
- terminal policy: `strict`
- ERROR: **0**, AMBIGUOUS: 0, WARNING: 0

## Variant v1

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 229868588.8904298 | 229868588.890226 |
| chamber_construction_cost | 50000000 | 50000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 338995000 | 338995000.0 |
| calculated_cost | 568863588.8904297 | 568863588.890226 |
| new_network_length | 1553.6655696326086 | 1553.665569630668 |
| score | 20.58917719782986 | 20.58917719781833 |
| unconnected_oks_ids | [2, 5, 10] | [2, 5, 10] |
| coverage | | 14/17 |

segments 22 (special 0), new chambers 12, technical nodes 0, DN histogram {'100': 5, '125': 7, '150': 1, '200': 5, '250': 2, '300': 1, '65': 1}

## Variant v2

| metric | claimed | recomputed |
|---|---:|---:|
| construction_cost | 269083886.0645064 | 269083886.0642688 |
| chamber_construction_cost | 45000000 | 45000000.0 |
| existing_chamber_tie_in_count | 0 | 0 |
| existing_chamber_tie_in_cost | 0.0 | 0 |
| unconnected_penalty | 338995000 | 338995000.0 |
| calculated_cost | 608078886.0645064 | 608078886.0642688 |
| new_network_length | 1906.2081719957919 | 1906.2081719938155 |
| score | 22.744833325793554 | 22.74483332578097 |
| unconnected_oks_ids | [2, 5, 10] | [2, 5, 10] |
| coverage | | 14/17 |

segments 26 (special 1), new chambers 11, technical nodes 2, DN histogram {'100': 7, '125': 7, '150': 2, '200': 4, '250': 3, '300': 2, '65': 1}

## Findings

| level | code | variant | message |
|---|---|---|---|
