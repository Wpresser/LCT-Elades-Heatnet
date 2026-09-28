#!/usr/bin/env python3
"""Справочно (в стоимость и S не входит): как новые подключения меняют расход по существующей сети.

Техприложение §2.4: реконструкция и текущий расход существующей сети в обязательном расчёте не определяются,
во входе у существующей сети нет расходов. Здесь считается только ДОБАВЛЕННЫЙ расход: от каждой камеры
присоединения (heat_chamber с diag_tie_in или существующая камера — узел новой сети) по существующей сети к
источнику, и сравнивается с пропускной способностью существующего ДУ по таблице 1.

    python3 existing_added_flow.py ВХОД.geojson РЕЗУЛЬТАТ.geojson [--variant v1] [--md out.md]
"""
import argparse
import heapq
import json
from collections import defaultdict

from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import substring, transform

T = Transformer.from_crs(4326, 32637, always_xy=True).transform
CAP = {50: 3.5, 65: 8.3, 80: 13.2, 100: 22.3, 125: 40.2, 150: 65.1, 200: 152.3, 250: 274.9, 300: 437.4, 400: 943.1,
       500: 1663.4, 600: 2627.7, 700: 3735.1, 800: 5296.8, 900: 7165.0, 1000: 9391.8, 1200: 15012.8, 1400: 22501.9}
SNAP = 0.1  # м: концы участков и точки присоединения


def min_dn(flow):
    return next((dn for dn in sorted(CAP) if CAP[dn] >= flow - 1e-9), None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("result")
    ap.add_argument("--variant", default=None)
    ap.add_argument("--md", default=None)
    a = ap.parse_args()
    inp = json.load(open(a.input, encoding="utf-8-sig"))["features"]
    res = json.load(open(a.result, encoding="utf-8"))["features"]
    pipes = {}
    for f in inp:
        p = f["properties"]
        if p.get("object_type") == "heat_network":
            pipes[str(p["id"])] = (int(p["diameter"]), transform(T, shape(f["geometry"])))
    src = next(transform(T, shape(f["geometry"])) for f in inp if f["properties"].get("object_type") == "source")
    ex_ch = {str(f["properties"]["id"]): transform(T, shape(f["geometry"])) for f in inp
             if f["properties"].get("object_type") == "heat_chamber"}
    variants = sorted({f["properties"]["variant_id"] for f in res if f["properties"].get("variant_id")})
    out_md = []
    for v in variants if a.variant is None else [a.variant]:
        feats = [f for f in res if f["properties"].get("variant_id") == v]
        lines = [f["properties"] for f in feats if f["properties"]["object_type"] == "heat_network"]
        # точки присоединения и добавляемый расход (сумма расходов новых участков, примыкающих к узлу)
        ties = {}
        for f in feats:
            p = f["properties"]
            if p["object_type"] == "heat_chamber" and p.get("diag_tie_in"):
                ties[p["id"]] = transform(T, shape(f["geometry"]))
        for l in lines:
            for n in (l["start_node_id"], l["end_node_id"]):
                if str(n) in ex_ch:
                    ties[str(n)] = ex_ch[str(n)]
        add = defaultdict(float)
        for tid in ties:
            add[tid] = sum(l["flow_tph"] for l in lines if str(l["start_node_id"]) == str(tid) or str(l["end_node_id"]) == str(tid))
        # граф существующей сети: участки режутся в точках присоединения
        cuts = defaultdict(list)
        for tid, pt in ties.items():
            pid = min(pipes, key=lambda k: pipes[k][1].distance(pt))
            cuts[pid].append((pipes[pid][1].project(pt), tid))
        nodes, edges = [], []

        def node(pt):
            for i, q in enumerate(nodes):
                if q.distance(pt) <= SNAP:
                    return i
            nodes.append(pt)
            return len(nodes) - 1
        tie_node = {}
        for pid, (dn, g) in pipes.items():
            marks = sorted([(0.0, None), (g.length, None)] + cuts.get(pid, []))
            for (s0, t0), (s1, t1) in zip(marks, marks[1:]):
                if s1 - s0 < 1e-6:
                    continue
                seg = substring(g, s0, s1)
                a0, a1 = node(Point(seg.coords[0])), node(Point(seg.coords[-1]))
                edges.append((a0, a1, pid, dn, seg.length))
            for s, tid in cuts.get(pid, []):
                tie_node[tid] = node(Point(g.interpolate(s).coords[0]))
        adj = defaultdict(list)
        for i, (a0, a1, pid, dn, ln) in enumerate(edges):
            adj[a0].append((a1, i))
            adj[a1].append((a0, i))
        s_node = min(range(len(nodes)), key=lambda i: nodes[i].distance(src))
        # кратчайшие пути до источника (сеть — дерево; Дейкстра на случай колец)
        dist = {s_node: 0.0}
        prev = {}
        pq = [(0.0, s_node)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist.get(u, 1e18):
                continue
            for w, ei in adj[u]:
                nd = d + edges[ei][4]
                if nd < dist.get(w, 1e18):
                    dist[w], prev[w] = nd, (u, ei)
                    heapq.heappush(pq, (nd, w))
        load = defaultdict(float)
        seg_len = defaultdict(float)
        for tid, n in tie_node.items():
            u = n
            while u in prev:
                u2, ei = prev[u]
                load[ei] += add[tid]
                u = u2
        per_pipe = defaultdict(lambda: [0.0, 0.0])
        for ei, fl in load.items():
            a0, a1, pid, dn, ln = edges[ei]
            pp = per_pipe[pid]
            pp[0] = max(pp[0], fl)
            pp[1] += ln
        rows = sorted(per_pipe.items(), key=lambda kv: -kv[1][0])
        total = sum(add.values())
        worst = max((fl / CAP[pipes[pid][0]] for pid, (fl, _) in rows), default=0)
        out_md.append(f"### {v}: присоединений {len(ties)}, добавленный расход {total:.2f} т/ч, "
                      f"участков существующей сети на путях к источнику {len(rows)}, наибольшая загрузка ДУ "
                      f"добавленным расходом {worst * 100:.0f} %\n")
        out_md.append("| участок | ДУ | пропускная способность, т/ч | добавленный расход, т/ч | ДУ по добавленному расходу | увеличение ДУ по добавленному расходу |")
        out_md.append("|---|---|---|---|---|---|")
        for pid, (fl, ln) in rows:
            dn = pipes[pid][0]
            need = min_dn(fl)
            out_md.append(f"| {pid} | {dn} | {CAP[dn]} | {fl:.2f} | {need} | {'нужно' if need and need > dn else 'не нужно'} |")
        out_md.append("")
    text = "\n".join(out_md)
    print(text)
    if a.md:
        open(a.md, "w", encoding="utf-8").write(text + "\n")


if __name__ == "__main__":
    main()
