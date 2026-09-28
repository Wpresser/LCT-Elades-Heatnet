"""Самопроверка валидатора: корректные сценарии проходят, испорченные копии ловятся.

    .venv/bin/python -m unittest test_validator -v

Ожидаемые стоимости посчитаны вручную по таблицам техприложения (в комментариях), а не функциями rules.py.
"""
import copy
import json
import os
import tempfile
import unittest

from pyproj import Transformer

import validator as V

TO_LL = Transformer.from_crs("EPSG:32637", "EPSG:4326", always_xy=True)
OX, OY = 413000.0, 6172000.0   # начало локальных метров (район конкурсного набора)


def ll(x, y):
    lon, lat = TO_LL.transform(OX + x, OY + y)
    return [lon, lat]


def feat(props, geom):
    return {"type": "Feature", "properties": props, "geometry": geom}


def pt(x, y):
    return {"type": "Point", "coordinates": ll(x, y)}


def line(*xy):
    return {"type": "LineString", "coordinates": [ll(x, y) for x, y in xy]}


def poly(x0, y0, x1, y1):
    return {"type": "Polygon", "coordinates": [[ll(x0, y0), ll(x1, y0), ll(x1, y1), ll(x0, y1), ll(x0, y0)]]}


def net(fid, a, b, flow, dn, length, cost, *xy, laying="base", variant="v1"):
    return feat({"id": fid, "object_type": "heat_network", "variant_id": variant, "start_node_id": a, "end_node_id": b,
                 "flow_tph": flow, "diameter": dn, "length": length, "laying_method": laying,
                 "depth_start": None, "depth_end": None, "cost": cost}, line(*xy))


def summary(variant, rank, construction, chambers, tie_count, penalty, length, score, unconnected):
    return feat({"id": f"{variant}_summary", "object_type": "variant_summary", "variant_id": variant, "rank": rank,
                 "construction_cost": construction, "chamber_construction_cost": chambers,
                 "existing_chamber_tie_in_count": tie_count, "existing_chamber_tie_in_cost": tie_count * 5_000_000,
                 "unconnected_penalty": penalty, "calculated_cost": construction + penalty,
                 "new_network_length": length, "score": score, "unconnected_oks_ids": unconnected}, None)


def fc(features):
    return {"type": "FeatureCollection", "features": features}


# --- сценарий A: пример техприложения §7.3 (100 м ДУ 100 + врезка в существующую камеру)
INPUT_A = fc([
    feat({"id": 900, "object_type": "heat_network", "diameter": 400}, line((0, 0), (-200, 0))),
    feat({"id": "input_chamber_1", "object_type": "heat_chamber"}, pt(0, 0)),
    feat({"id": "input_oks_1", "object_type": "oks_connection_point", "flow_tph": 20.0}, pt(0, 100)),
])
# 100 · 89 748 = 8 974 800; + 5 000 000 = 13 974 800; S = 0,7·13 974 800/25e6 + 0,3·100/100 = 0,6912944
OUTPUT_A = fc([
    net("v1_net_1", "input_chamber_1", "input_oks_1", 20.0, 100, 100.0, 8_974_800, (0, 0), (0, 100)),
    summary("v1", 1, 13_974_800, 0, 1, 0, 100.0, 0.6912944, []),
])

# --- сценарий B: новая камера на трубе ДУ 500, разветвление в новой камере, подход в свой ОКС
INPUT_B = fc([
    feat({"id": 1, "object_type": "heat_network", "diameter": 500}, line((0, 0), (400, 0))),
    feat({"id": 5, "object_type": "heat_chamber"}, pt(400, 0)),
    feat({"id": 11, "object_type": "oks_connection_point", "flow_tph": 30.0}, pt(100, 60)),
    feat({"id": 12, "object_type": "oks_connection_point", "flow_tph": 20.0}, pt(160, 60)),
    feat({"id": 21, "object_type": "restriction", "restriction_type": "oks"}, poly(90, 55, 110, 75)),
])
# N1→B 30 м ДУ150: 30·105 507 = 3 165 210; B→11 60 м ДУ125: 60·97 275 = 5 836 500; B→12 60 м ДУ100: 5 384 880
# камеры: N1 = max(150, 500 существующая) → 5 млн; B = 150 → 3 млн. Итого 22 386 590; S = 0,62682452 + 0,45
FEATURES_B = [
    feat({"id": "v1_ch_1", "object_type": "heat_chamber", "variant_id": "v1", "diameter": 500, "cost": 5_000_000}, pt(130, 0)),
    feat({"id": "v1_ch_2", "object_type": "heat_chamber", "variant_id": "v1", "diameter": 150, "cost": 3_000_000}, pt(130, 30)),
    net("v1_net_1", "v1_ch_1", "v1_ch_2", 50.0, 150, 30.0, 3_165_210, (130, 0), (130, 30)),
    net("v1_net_2", "v1_ch_2", 11, 30.0, 125, 60.0, 5_836_500, (130, 30), (100, 30), (100, 60)),
    net("v1_net_3", "v1_ch_2", 12, 20.0, 100, 60.0, 5_384_880, (130, 30), (160, 30), (160, 60)),
]
OUTPUT_B = fc(FEATURES_B + [summary("v1", 1, 22_386_590, 8_000_000, 0, 0, 150.0, 1.07682452, [])])

# --- сценарий C: B + дорога поперёк ветки к цели 12 → спецпроход 37..51 м (полигон 40..48 + по 3 м)
INPUT_C = copy.deepcopy(INPUT_B)
INPUT_C["features"].append(feat({"id": 31, "object_type": "restriction", "restriction_type": "road"}, poly(150, 40, 170, 48)))
# ветка к 12: база 37 м = 3 320 676; спец 14 м · 89 748 · 1,6 = 2 010 355,2; база 9 м = 807 732 → 6 138 763,2
# трубы 15 140 473,2 + камеры 8 млн = 23 140 473,2; S = 0,7·0,925618928 + 0,45 = 1,09793325
FEATURES_C = FEATURES_B[:4] + [
    feat({"id": "v1_tn_1", "object_type": "technical_node", "variant_id": "v1"}, pt(160, 37)),
    feat({"id": "v1_tn_2", "object_type": "technical_node", "variant_id": "v1"}, pt(160, 51)),
    net("v1_net_3", "v1_ch_2", "v1_tn_1", 20.0, 100, 37.0, 3_320_676, (130, 30), (160, 30), (160, 37)),
    net("v1_net_4", "v1_tn_1", "v1_tn_2", 20.0, 100, 14.0, 2_010_355.2, (160, 37), (160, 51), laying="special"),
    net("v1_net_5", "v1_tn_2", 12, 20.0, 100, 9.0, 807_732, (160, 51), (160, 60)),
]
OUTPUT_C = fc(FEATURES_C + [summary("v1", 1, 23_140_473.2, 8_000_000, 0, 0, 150.0, 1.09793325, [])])


def by_id(collection, fid):
    return next(f for f in collection["features"] if f["properties"]["id"] == fid)


class ValidatorTest(unittest.TestCase):

    def run_validator(self, inp, out):
        with tempfile.TemporaryDirectory() as d:
            ip, op = os.path.join(d, "in.geojson"), os.path.join(d, "out.geojson")
            with open(ip, "w") as f:
                json.dump(inp, f)
            with open(op, "w") as f:
                json.dump(out, f)
            rep, results = V.validate(ip, op)
        return rep, results

    def codes(self, inp, out):
        rep, _ = self.run_validator(inp, out)
        return {i["code"] for i in rep.errors()}

    def assert_valid(self, inp, out):
        rep, results = self.run_validator(inp, out)
        self.assertEqual([], [f"{i['code']}: {i['message']}" for i in rep.errors()])
        return results

    # --- корректные сценарии

    def test_appendix_example_is_valid(self):
        r = self.assert_valid(INPUT_A, OUTPUT_A)
        self.assertAlmostEqual(0.6912944, r["'v1'"]["recomputed"]["score"], places=9)

    def test_branching_network_is_valid(self):
        r = self.assert_valid(INPUT_B, OUTPUT_B)
        self.assertAlmostEqual(22_386_590, r["'v1'"]["recomputed"]["construction_cost"], places=3)

    def test_road_special_crossing_is_valid(self):
        r = self.assert_valid(INPUT_C, OUTPUT_C)
        self.assertAlmostEqual(23_140_473.2, r["'v1'"]["recomputed"]["construction_cost"], places=3)

    # --- испорченные копии

    def test_missing_length(self):
        out = copy.deepcopy(OUTPUT_A)
        del by_id(out, "v1_net_1")["properties"]["length"]
        self.assertIn("line.length_missing", self.codes(INPUT_A, out))

    def test_wrong_cost(self):
        out = copy.deepcopy(OUTPUT_A)
        by_id(out, "v1_net_1")["properties"]["cost"] = 8_000_000
        self.assertIn("cost.line", self.codes(INPUT_A, out))

    def test_wrong_score(self):
        out = copy.deepcopy(OUTPUT_A)
        by_id(out, "v1_summary")["properties"]["score"] = 0.69
        self.assertIn("summary.score", self.codes(INPUT_A, out))

    def test_endpoint_not_at_node(self):
        out = copy.deepcopy(OUTPUT_A)
        by_id(out, "v1_net_1")["geometry"] = line((0, 0), (0, 99))
        self.assertIn("ref.geometry", self.codes(INPUT_A, out))

    def test_sharp_turn(self):
        out = copy.deepcopy(OUTPUT_B)
        by_id(out, "v1_net_3")["geometry"] = line((130, 30), (170, 30), (158, 33), (160, 60))
        self.assertIn("geom.turn", self.codes(INPUT_B, out))

    def test_final_approach_not_through_nearest_boundary(self):
        out = copy.deepcopy(OUTPUT_B)
        by_id(out, "v1_net_2")["geometry"] = line((130, 30), (130, 65), (100, 60))
        self.assertIn("oks.final_approach", self.codes(INPUT_B, out))

    def test_crossing_road_without_special(self):
        out = copy.deepcopy(OUTPUT_C)
        by_id(out, "v1_net_4")["properties"]["laying_method"] = "base"
        self.assertIn("clearance.road", self.codes(INPUT_C, out))

    def test_special_too_short(self):
        out = copy.deepcopy(OUTPUT_C)
        by_id(out, "v1_net_4")["geometry"] = line((160, 37), (160, 49))
        self.assertIn("special.extent", self.codes(INPUT_C, out))

    def test_special_cost_without_coefficient(self):
        out = copy.deepcopy(OUTPUT_C)
        by_id(out, "v1_net_4")["properties"]["cost"] = 14 * 89_748
        self.assertIn("cost.line", self.codes(INPUT_C, out))

    def test_cycle(self):
        out = copy.deepcopy(OUTPUT_B)
        out["features"].append(net("v1_net_9", 11, 12, 1.0, 50, 60.0, 60 * 74_023, (100, 60), (160, 60)))
        self.assertIn("topo.cycle", self.codes(INPUT_B, out))

    def test_diameter_too_small_and_decreasing(self):
        out = copy.deepcopy(OUTPUT_B)
        by_id(out, "v1_net_1")["properties"]["diameter"] = 100
        c = self.codes(INPUT_B, out)
        self.assertIn("dn.capacity", c)
        self.assertIn("dn.decrease", c)

    def test_flow_mismatch(self):
        out = copy.deepcopy(OUTPUT_B)
        by_id(out, "v1_net_1")["properties"]["flow_tph"] = 30.0
        self.assertIn("flow.mismatch", self.codes(INPUT_B, out))

    def test_unconnected_list_mismatch(self):
        out = copy.deepcopy(OUTPUT_B)
        out["features"] = [f for f in out["features"] if f["properties"]["id"] != "v1_net_3"]
        self.assertIn("summary.unconnected_ids", self.codes(INPUT_B, out))

    def test_ten_metre_rule(self):
        inp = copy.deepcopy(INPUT_A)
        # присоединение новой камерой в 6 м от существующей камеры с запасом примыканий
        out = fc([
            feat({"id": "v1_ch_1", "object_type": "heat_chamber", "variant_id": "v1", "diameter": 400, "cost": 5_000_000}, pt(-6, 0)),
            net("v1_net_1", "v1_ch_1", "input_oks_1", 20.0, 100, 100.18, 100.18 * 89_748, (-6, 0), (0, 100)),
            summary("v1", 1, 5_000_000 + 100.18 * 89_748, 5_000_000, 0, 0, 100.18, 0.0, []),
        ])
        self.assertIn("tie_in.ten_metres", self.codes(inp, out))

    def test_rank_order(self):
        # v2 — тот же подход с обходом (длиннее и дороже), но ему присвоен rank 1
        out = copy.deepcopy(OUTPUT_A)
        by_id(out, "v1_summary")["properties"]["rank"] = 2
        out["features"] += [
            net("v2_net_1", "input_chamber_1", "input_oks_1", 20.0, 100, 107.7, 107.7 * 89_748,
                (0, 0), (20, 50), (0, 100), variant="v2"),
            summary("v2", 1, 0, 0, 1, 0, 0, 0, []),
        ]
        rep, _ = self.run_validator(INPUT_A, out)
        rank_errors = [i for i in rep.errors() if i["code"] == "summary.rank"]
        self.assertEqual({"'v1'", "'v2'"}, {i["variant"] for i in rank_errors})

    def test_literal_two_metre_special_conflicts_with_clearance(self):
        """Воспроизводимый тест конфликта (DECISIONS №17): газопровод, ДУ 100, пересечение под 90°,
        спецучасток ровно ± 2 м. Отступ 2,0 м между габаритами требует 2,0 + 0,2 + 0,255 = 2,455 м между осями,
        а граница спецучастка в 2 м от оси — буквальное правило нарушается сразу за спецучастком."""
        inp = copy.deepcopy(INPUT_A)
        inp["features"].append(feat({"id": 50, "object_type": "restriction", "restriction_type": "gas_pipeline"},
                                    line((-100, 50), (100, 50))))
        # 48 м базы + 4 м спец (K 1,25) + 48 м базы; ДУ 100
        out = fc([
            feat({"id": "v1_tn_1", "object_type": "technical_node", "variant_id": "v1"}, pt(0, 48)),
            feat({"id": "v1_tn_2", "object_type": "technical_node", "variant_id": "v1"}, pt(0, 52)),
            net("v1_net_1", "input_chamber_1", "v1_tn_1", 20.0, 100, 48.0, 48 * 89_748, (0, 0), (0, 48)),
            net("v1_net_2", "v1_tn_1", "v1_tn_2", 20.0, 100, 4.0, 4 * 89_748 * 1.25, (0, 48), (0, 52), laying="special"),
            net("v1_net_3", "v1_tn_2", "input_oks_1", 20.0, 100, 48.0, 48 * 89_748, (0, 52), (0, 100)),
            summary("v1", 1, 100 * 89_748 + 4 * 89_748 * 0.25 + 5_000_000, 0, 1, 0, 100.0, 0.0, []),
        ])
        self.assertIn("clearance.gas_pipeline", self.codes(inp, out))

    def test_string_and_number_ids_are_distinct(self):
        out = copy.deepcopy(OUTPUT_B)
        by_id(out, "v1_net_2")["properties"]["end_node_id"] = "11"
        self.assertIn("ref.missing", self.codes(INPUT_B, out))


if __name__ == "__main__":
    unittest.main()
