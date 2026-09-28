#!/usr/bin/env python3
"""Проверка результатов сценариев: валидатор без ошибок + ожидания expected.json для варианта rank 1.

    ../validator/.venv/bin/python check_scenarios.py ../service/target/scenarios
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "validator"))
import validator as V  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    results_root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "service", "target", "scenarios")
    failed = 0
    for name in sorted(os.listdir(HERE)):
        inp = os.path.join(HERE, name, "input.geojson")
        if not os.path.isfile(inp):
            continue
        out = os.path.join(results_root, name, "result.geojson")
        exp = json.load(open(os.path.join(HERE, name, "expected.json"), encoding="utf-8"))
        problems = []
        if not os.path.isfile(out):
            problems.append("нет результата")
        else:
            rep, _ = V.validate(inp, out)
            problems += [f"{i['variant']} {i['code']}: {i['message']}" for i in rep.errors()]
            res = json.load(open(out, encoding="utf-8"))
            best = next(f["properties"] for f in res["features"]
                        if f["properties"]["object_type"] == "variant_summary" and f["properties"]["rank"] == 1)
            vid = best["variant_id"]
            unconnected = set(map(str, best["unconnected_oks_ids"]))
            if exp.get("connected_all") and unconnected:
                problems.append(f"все цели должны быть подключены, не подключены: {sorted(unconnected)}")
            for t in exp.get("connected", []):
                if str(t) in unconnected:
                    problems.append(f"цель {t} должна быть подключена")
            for t in exp.get("unconnected", []):
                if str(t) not in unconnected:
                    problems.append(f"цель {t} должна остаться неподключённой")
            crossed = set()
            for f in res["features"]:
                p = f["properties"]
                if p.get("variant_id") == vid and p.get("laying_method") == "special":
                    crossed |= {c.split(":")[0] for c in p.get("diag_crossing", [])}
            for t in exp.get("special_types", []):
                if t not in crossed:
                    problems.append(f"ожидался спецпроход через {t}, есть {sorted(crossed)}")
        status = "OK" if not problems else "FAIL"
        failed += bool(problems)
        print(f"{status:4} {name}")
        for p in problems[:8]:
            print("       ", p)
    print(f"сценариев с проблемами: {failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
