#!/usr/bin/env python3
"""
Независимый валидатор результата ЛЦТ 2026, задача 2.

Проверяет выходной GeoJSON по техническому приложению (A) и разъяснениям (B), пересчитывает длины,
стоимости и score. Не использует код Java-сервиса: правила переписаны прямо из документов (rules.py).

    python validator.py INPUT.geojson OUTPUT.geojson [--json report.json]

Код возврата: 0 — ошибок нет, 1 — есть нарушения, 2 — файлы не читаются.
Трактовки спорных мест перечислены в DECISIONS.md репозитория.
"""
import argparse
import json
import math
import sys
from collections import defaultdict

from pyproj import Transformer
from shapely.geometry import LineString, Point, shape
from shapely.ops import nearest_points, transform as shp_transform, unary_union
from shapely.strtree import STRtree

import rules as R

TO_M = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)

INPUT_TOPO_TOL = 0.05   # м, совпадение камер/источника с концами существующих линий
EPS_CLEAR = 1e-3        # м, численный допуск проверки отступов
MONEY_TOL = 1.0         # руб
SCORE_TOL = 1e-6
LEN_TOL = 0.01          # м
TURN_TOL = 1e-6         # град
SPECIAL_EXT = 60.0      # м, насколько продлевать спецучасток при поиске зон

OUT_TYPES = {"heat_network", "heat_chamber", "technical_node", "variant_summary"}


def to_metric(geom):
    return shp_transform(TO_M.transform, geom)


def id_key(v):
    """ID сравниваются с учётом типа: строка '1' и число 1 — разные идентификаторы."""
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        return ("n", float(v))
    if isinstance(v, str):
        return ("s", v)
    return None


def fmt_id(k):
    return repr(k[1] if k[0] == "s" else (int(k[1]) if float(k[1]).is_integer() else k[1]))


class Report:
    def __init__(self):
        self.items = []

    def add(self, level, variant, code, msg):
        self.items.append({"level": level, "variant": variant, "code": code, "message": msg})

    def err(self, variant, code, msg):
        self.add("error", variant, code, msg)

    def warn(self, variant, code, msg):
        self.add("warning", variant, code, msg)

    def errors(self, variant=None):
        return [i for i in self.items if i["level"] == "error" and (variant is None or i["variant"] == variant)]


# ---------------------------------------------------------------- вход

class InputData:
    def __init__(self, path, report):
        with open(path, encoding="utf-8") as f:
            fc = json.load(f)
        self.targets = {}     # key -> dict(id, flow, pt)
        self.invalid_targets = {}  # missing geometry: retain target and §6 penalty, never route
        self.chambers = {}    # key -> dict(id, pt, adj)
        self.pipes = []       # dict(id, dn, line)
        self.restrictions = []  # dict(id, type, geom)
        for feat in fc.get("features", []):
            p = feat.get("properties") or {}
            t = p.get("object_type")
            k = id_key(p.get("id"))
            g = feat.get("geometry")
            if k is not None and t == "oks_connection_point" and g is None:
                flow = p.get("flow_tph")
                flow = float(flow) if isinstance(flow, (int, float)) and not isinstance(flow, bool) and math.isfinite(flow) and flow >= 0 else 0.0
                self.invalid_targets[k] = dict(id=p["id"], flow=flow)
                report.warn(None, "input.invalid_target_geometry", f"цель {fmt_id(k)} без геометрии: учитывается как неподключённая со штрафом §6")
                continue
            if k is None or g is None:
                continue
            gm = to_metric(shape(g))
            if t == "oks_connection_point" and isinstance(p.get("flow_tph"), (int, float)):
                self.targets[k] = dict(id=p["id"], flow=float(p["flow_tph"]), pt=gm)
            elif t == "heat_chamber":
                self.chambers[k] = dict(id=p["id"], pt=gm, adj=0)
            elif t == "heat_network" and isinstance(p.get("diameter"), (int, float)):
                parts = [gm] if gm.geom_type == "LineString" else list(getattr(gm, "geoms", []))
                for part in parts:
                    self.pipes.append(dict(id=p["id"], dn=int(p["diameter"]), line=part))
            elif t == "restriction" and p.get("restriction_type") in R.RESTRICTIONS:
                if gm.geom_type in ("Polygon", "MultiPolygon") and not gm.is_valid:
                    gm = gm.buffer(0)
                self.restrictions.append(dict(id=p["id"], type=p["restriction_type"], geom=gm))
        both = set(self.targets) & set(self.chambers)
        if both:
            report.warn(None, "input.ambiguous_id", f"ID совпадают у целей и камер: {sorted(map(fmt_id, both))}")
        self.pipe_tree = STRtree([p["line"] for p in self.pipes]) if self.pipes else None
        self.restr_tree = STRtree([r["geom"] for r in self.restrictions]) if self.restrictions else None
        for c in self.chambers.values():
            c["adj"] = self.existing_adjacency(c["pt"])
        # «свой» ОКС — весь объект, в части которого лежит цель; part — эта часть (для ближайшего контура), DECISIONS №26
        self.own_oks = {}
        for k, t in self.targets.items():
            own = []
            for r in self.restrictions_near(t["pt"], 0):
                if r["type"] != "oks":
                    continue
                for part in getattr(r["geom"], "geoms", [r["geom"]]):
                    if part.geom_type == "Polygon" and part.covers(t["pt"]):
                        own.append(dict(id=r["id"], geom=part, restriction=r))
            self.own_oks[k] = own

    def pipes_near(self, geom, dist):
        if self.pipe_tree is None:
            return []
        return [self.pipes[i] for i in self.pipe_tree.query(geom.buffer(dist) if dist > 0 else geom)]

    def restrictions_near(self, geom, dist):
        if self.restr_tree is None:
            return []
        return [self.restrictions[i] for i in self.restr_tree.query(geom.buffer(dist) if dist > 0 else geom)]

    def existing_adjacency(self, pt):
        """Конец существующей линии в точке = 1 примыкание, линия проходит через точку = 2 (§2.1)."""
        adj = 0
        for p in self.pipes_near(pt, INPUT_TOPO_TOL):
            cs = list(p["line"].coords)
            ends = sum(1 for c in (cs[0], cs[-1]) if Point(c).distance(pt) <= INPUT_TOPO_TOL)
            if ends:
                adj += ends
            elif p["line"].distance(pt) <= INPUT_TOPO_TOL:
                adj += 2
        return adj

    def on_existing_network(self, pt):
        return any(p["line"].distance(pt) <= INPUT_TOPO_TOL for p in self.pipes_near(pt, INPUT_TOPO_TOL))


# ---------------------------------------------------------------- геометрия

def turn_deg(a, b, c):
    """Угол поворота в b: 0 — прямо, 180 — разворот."""
    v1 = (b[0] - a[0], b[1] - a[1])
    v2 = (c[0] - b[0], c[1] - b[1])
    n1 = math.hypot(*v1)
    n2 = math.hypot(*v2)
    if n1 == 0 or n2 == 0:
        return None
    cosv = max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))
    return math.degrees(math.acos(cosv))


def crossing_angle(seg_dir, other_dir):
    """Угол между направлениями в диапазоне [0, 90]."""
    a = math.degrees(math.atan2(seg_dir[1], seg_dir[0]) - math.atan2(other_dir[1], other_dir[0])) % 180.0
    return min(a, 180.0 - a)


def boundary_dir_at(geom, pt):
    """Направление границы полигона (или линии) в точке."""
    lines = []
    if geom.geom_type in ("Polygon", "MultiPolygon"):
        b = geom.boundary
        lines = list(b.geoms) if hasattr(b, "geoms") else [b]
    else:
        lines = list(geom.geoms) if hasattr(geom, "geoms") else [geom]
    best = None
    for ln in lines:
        cs = list(ln.coords)
        for i in range(len(cs) - 1):
            s = LineString([cs[i], cs[i + 1]])
            d = s.distance(pt)
            if best is None or d < best[0]:
                best = (d, (cs[i + 1][0] - cs[i][0], cs[i + 1][1] - cs[i][1]))
    return best[1] if best else None


def intervals_along(line, geom):
    """Интервалы [s0, s1] вдоль line, где line пересекает geom (точки дают s0 == s1)."""
    inter = line.intersection(geom)
    out = []
    parts = list(inter.geoms) if hasattr(inter, "geoms") else [inter]
    for part in parts:
        if part.is_empty:
            continue
        if part.geom_type == "Point":
            s = line.project(part)
            out.append((s, s))
        elif part.geom_type == "LineString":
            s0 = line.project(Point(part.coords[0]))
            s1 = line.project(Point(part.coords[-1]))
            out.append((min(s0, s1), max(s0, s1)))
    return sorted(out)


# ---------------------------------------------------------------- проверка варианта

class VariantChecker:
    def __init__(self, vid, feats, inp, report, node_tol):
        self.vid = vid
        self.inp = inp
        self.rep = report
        self.tol = node_tol
        self.lines = []      # dict(key, props, geom)
        self.nodes = {}      # key -> dict(type, props, pt)
        self.summary = None
        for f in feats:
            p = f["properties"]
            t = p["object_type"]
            if t == "heat_network":
                self.lines.append(dict(key=id_key(p["id"]), p=p, geom=to_metric(shape(f["geometry"]))))
            elif t in ("heat_chamber", "technical_node"):
                self.nodes[id_key(p["id"])] = dict(type=t, p=p, pt=to_metric(shape(f["geometry"])))
            elif t == "variant_summary":
                if self.summary is not None:
                    self.err("summary.duplicate", "в варианте больше одной variant_summary")
                self.summary = p

    def err(self, code, msg):
        self.rep.err(self.vid, code, msg)

    def warn(self, code, msg):
        self.rep.warn(self.vid, code, msg)

    # --- узлы

    def resolve(self, key):
        """(вид узла, точка): out_chamber | out_tn | target | in_chamber."""
        found = []
        if key in self.nodes:
            n = self.nodes[key]
            found.append(("out_chamber" if n["type"] == "heat_chamber" else "out_tn", n["pt"]))
        if key in self.inp.targets:
            found.append(("target", self.inp.targets[key]["pt"]))
        if key in self.inp.chambers:
            found.append(("in_chamber", self.inp.chambers[key]["pt"]))
        if len(found) > 1:
            self.err("ref.ambiguous", f"ID узла {fmt_id(key)} совпадает у нескольких объектов")
        return found[0] if found else (None, None)

    def run(self):
        if self.summary is None:
            self.err("summary.missing", "нет объекта variant_summary")
        self.check_lines_basic()
        self.build_graph()
        self.check_topology()
        self.check_turns()
        self.check_new_line_crossings()
        self.orient_and_flows()
        self.check_special_runs()
        self.check_clearances()
        self.check_costs_and_summary()

    def check_lines_basic(self):
        for ln in self.lines:
            p, g = ln["p"], ln["geom"]
            name = fmt_id(ln["key"])
            dn = p.get("diameter")
            if not isinstance(dn, int) or dn not in R.DN_TABLE:
                self.err("line.diameter", f"участок {name}: diameter={dn!r} нет в таблице 1")
                ln["dn"] = None
            else:
                ln["dn"] = dn
            ln["len"] = g.length
            if not isinstance(p.get("length"), (int, float)):
                self.err("line.length_missing", f"участок {name}: нет числового length (§7.2)")
            elif abs(p["length"] - g.length) > LEN_TOL + 1e-6 * g.length:
                self.err("line.length", f"участок {name}: length={p['length']:.3f}, по геометрии {g.length:.3f} м")
            if p.get("laying_method") not in ("base", "special"):
                self.err("line.laying", f"участок {name}: laying_method={p.get('laying_method')!r}")
            if p.get("depth_start", "x") is not None or p.get("depth_end", "x") is not None:
                self.err("line.depth", f"участок {name}: в 2D depth_start/depth_end должны быть null")
            cs = list(g.coords)
            for i in range(len(cs) - 1):
                if cs[i] == cs[i + 1]:
                    self.err("line.duplicate_vertex", f"участок {name}: повторяющаяся вершина №{i + 1}")
            for end, field, c in (("начало", "start_node_id", cs[0]), ("конец", "end_node_id", cs[-1])):
                k = id_key(p.get(field))
                kind, pt = self.resolve(k) if k else (None, None)
                if kind is None:
                    self.err("ref.missing", f"участок {name}: {field}={p.get(field)!r} не найден среди узлов")
                    ln[field] = None
                    continue
                ln[field] = k
                d = Point(c).distance(pt)
                if d > self.tol:
                    self.err("ref.geometry", f"участок {name}: {end} линии в {d:.3f} м от узла {fmt_id(k)} (допуск {self.tol} м)")

    def build_graph(self):
        self.adj = defaultdict(list)   # node -> [(line, other)]
        for ln in self.lines:
            a, b = ln.get("start_node_id"), ln.get("end_node_id")
            if a is None or b is None:
                continue
            if a == b:
                self.err("topo.loop", f"участок {fmt_id(ln['key'])} начинается и заканчивается в одном узле")
                continue
            self.adj[a].append((ln, b))
            self.adj[b].append((ln, a))
        # точки присоединения к существующей сети
        self.roots = set()
        for k in self.adj:
            kind, pt = self.resolve(k)
            if kind == "in_chamber" or (kind == "out_chamber" and self.inp.on_existing_network(pt)):
                self.roots.add(k)

    def check_topology(self):
        # циклы
        parent = {}

        def find(x):
            parent.setdefault(x, x)
            while parent[x] != x:
                parent[x] = parent[parent[x]]
                x = parent[x]
            return x

        for ln in self.lines:
            a, b = ln.get("start_node_id"), ln.get("end_node_id")
            if a is None or b is None or a == b:
                continue
            ra, rb = find(a), find(b)
            if ra == rb:
                self.err("topo.cycle", f"участок {fmt_id(ln['key'])} замыкает цикл (§2.1: замкнутые маршруты недопустимы)")
            else:
                parent[ra] = rb
        comps = defaultdict(set)
        for k in self.adj:
            comps[find(k)].add(k)
        self.component_of = {}
        self.component_has_root = {}
        for cid, members in comps.items():
            for m in members:
                self.component_of[m] = cid
            roots = [m for m in members if m in self.roots]
            self.component_has_root[cid] = len(roots) >= 1
            targets = [m for m in members if self.resolve(m)[0] == "target"]
            if len(roots) != 1:
                self.err("topo.roots", f"связная часть сети с узлами {sorted(map(fmt_id, members))[:6]}… "
                                       f"имеет {len(roots)} присоединений к существующей сети (нужно ровно 1)")
            if not targets:
                self.warn("topo.no_targets", f"связная часть с узлами {sorted(map(fmt_id, members))[:6]} не ведёт ни к одной цели")
        for k, lst in self.adj.items():
            kind, pt = self.resolve(k)
            deg = len(lst)
            if kind == "target" and deg > 1:
                self.err("topo.target_degree", f"к точке подключения {fmt_id(k)} примыкает {deg} участков")
            if kind == "out_tn" and deg != 2:
                self.err("topo.tn_degree", f"technical_node {fmt_id(k)}: {deg} участков (нужно 2, §2.1)")
            if deg >= 3 and kind not in ("out_chamber", "in_chamber"):
                self.err("topo.branch", f"разветвление в узле {fmt_id(k)} ({kind}) — разрешено только в камере")
            if kind == "in_chamber":
                total = self.inp.chambers[k]["adj"] + deg
                if total > 4:
                    self.err("topo.adjacency", f"камера {fmt_id(k)}: {total} примыканий > 4 (существующих {self.inp.chambers[k]['adj']})")
            if kind == "out_chamber":
                ex = self.inp.existing_adjacency(pt)
                if ex + deg > 4:
                    self.err("topo.adjacency", f"новая камера {fmt_id(k)}: {ex + deg} примыканий > 4")
                if k not in self.roots and deg < 3:
                    self.warn("topo.chamber_no_branch", f"новая камера {fmt_id(k)} не на сети и без разветвления ({deg} участка)")
                if k in self.roots:
                    self.check_ten_metre_rule(k, pt, deg)
        for k in self.nodes:
            if k not in self.adj:
                self.warn("topo.orphan_node", f"узел {fmt_id(k)} не связан ни с одним участком")

    def check_ten_metre_rule(self, k, pt, deg):
        """§2.4: точка присоединения ≤ 10 м от существующей камеры с запасом примыканий → использовать камеру."""
        for ck, c in self.inp.chambers.items():
            d = c["pt"].distance(pt)
            if d <= 10.0 + 1e-6:
                used = len(self.adj.get(ck, []))
                if c["adj"] + used + 1 <= 4:
                    self.err("tie_in.ten_metres", f"новая камера присоединения {fmt_id(k)} в {d:.2f} м от существующей камеры "
                                                  f"{fmt_id(ck)} с запасом примыканий — нужно присоединяться к ней (§2.4)")

    def check_turns(self):
        for ln in self.lines:
            cs = list(ln["geom"].coords)
            for i in range(1, len(cs) - 1):
                t = turn_deg(cs[i - 1], cs[i], cs[i + 1])
                if t is not None and t > 90.0 + TURN_TOL:
                    self.err("geom.turn", f"участок {fmt_id(ln['key'])}: поворот {t:.1f}° > 90° в вершине №{i}")
                seg = math.dist(cs[i], cs[i + 1])
                if 0 < seg < 0.5 and i + 1 < len(cs) - 1:
                    self.warn("geom.zigzag", f"участок {fmt_id(ln['key'])}: отрезок {seg:.2f} м между поворотами")
        # проходные узлы (technical_node, новая камера с двумя участками вне сети)
        for k, lst in self.adj.items():
            kind, pt = self.resolve(k)
            if len(lst) != 2 or kind in ("target", "in_chamber") or k in self.roots:
                continue
            (l1, _), (l2, _) = lst
            p1 = self.neighbour_vertex(l1["geom"], pt)
            p2 = self.neighbour_vertex(l2["geom"], pt)
            t = turn_deg(p1, (pt.x, pt.y), p2)
            if t is not None and t > 90.0 + TURN_TOL:
                self.err("geom.turn", f"поворот {t:.1f}° > 90° в узле {fmt_id(k)}")

    @staticmethod
    def neighbour_vertex(line, pt):
        cs = list(line.coords)
        if Point(cs[0]).distance(pt) <= Point(cs[-1]).distance(pt):
            return cs[1]
        return cs[-2]

    def check_new_line_crossings(self):
        geoms = [ln["geom"] for ln in self.lines]
        if len(geoms) < 2:
            return
        tree = STRtree(geoms)
        for i, ln in enumerate(self.lines):
            for j in tree.query(ln["geom"]):
                if j <= i:
                    continue
                other = self.lines[j]
                inter = ln["geom"].intersection(other["geom"])
                if inter.is_empty:
                    continue
                shared = {ln.get("start_node_id"), ln.get("end_node_id")} & {other.get("start_node_id"), other.get("end_node_id")}
                allowed = unary_union([self.resolve(k)[1] for k in shared if k is not None]) if shared else None
                rest = inter if allowed is None else inter.difference(allowed.buffer(self.tol + 1e-6))
                if not rest.is_empty:
                    self.err("geom.new_lines_cross", f"участки {fmt_id(ln['key'])} и {fmt_id(other['key'])} пересекаются вне общего узла (§2.1)")

    # --- расходы и ДУ

    def orient_and_flows(self):
        self.parent_line = {}   # line key -> line upstream (ближе к присоединению)
        self.down_node = {}     # line key -> узел дальше от присоединения
        self.expected_flow = {}
        for root in self.roots:
            # обход от присоединения
            order, seen, stack = [], {root}, [root]
            up_line = {root: None}
            while stack:
                n = stack.pop()
                order.append(n)
                for ln, other in self.adj[n]:
                    if other in seen:
                        continue
                    seen.add(other)
                    up_line[other] = ln
                    self.down_node[ln["key"]] = other
                    self.parent_line[ln["key"]] = up_line[n]
                    stack.append(other)
            sub = defaultdict(float)
            for n in reversed(order):
                if self.resolve(n)[0] == "target":
                    sub[n] += self.inp.targets[n]["flow"]
                if up_line[n] is not None:
                    parent_node = next(o for l2, o in self.adj[n] if l2 is up_line[n])
                    sub[parent_node] += sub[n]
                    self.expected_flow[up_line[n]["key"]] = sub[n]
        self.check_flows_and_dn()

    def check_flows_and_dn(self):
        by_key = {ln["key"]: ln for ln in self.lines}
        for ln in self.lines:
            k = ln["key"]
            name = fmt_id(k)
            if k not in self.expected_flow:
                continue
            exp = self.expected_flow[k]
            got = ln["p"].get("flow_tph")
            if not isinstance(got, (int, float)) or abs(got - exp) > 1e-6 * max(1.0, exp):
                self.err("flow.mismatch", f"участок {name}: flow_tph={got!r}, по сети ниже по течению {exp:.4f} т/ч (§2.3)")
            dn = ln["dn"]
            if dn is None:
                continue
            if R.capacity(dn) < exp - 1e-9:
                self.err("dn.capacity", f"участок {name}: ДУ {dn} пропускает {R.capacity(dn)} т/ч < {exp:.2f}")
            par = self.parent_line.get(k)
            if par is not None and par["dn"] is not None:
                if par["dn"] < dn:
                    self.err("dn.decrease", f"ДУ уменьшается к присоединению: {name} ДУ {dn} → {fmt_id(par['key'])} ДУ {par['dn']} (§2.3)")
                pexp = self.expected_flow.get(par["key"])
                if pexp is not None and abs(pexp - exp) <= 1e-9 and par["dn"] != dn:
                    self.err("dn.change_same_flow", f"ДУ меняется без изменения расхода: {name} ({dn}) и {fmt_id(par['key'])} ({par['dn']}) (§2.3)")
        # предельная длина по каждому пути от цели к присоединению
        for k in self.adj:
            if self.resolve(k)[0] != "target":
                continue
            path = []
            n = k
            while True:
                up = [ln for ln, _ in self.adj[n] if self.down_node.get(ln["key"]) == n]
                if not up:
                    break
                path.append(up[0])
                n = next(o for l2, o in self.adj[n] if l2 is up[0])
            run_dn, run_len, run_start = None, 0.0, None
            for ln in path + [None]:
                dn = ln["dn"] if ln else None
                if ln is not None and dn == run_dn:
                    run_len += ln["len"]
                    continue
                if run_dn is not None and run_len > R.max_length(run_dn) + LEN_TOL:
                    self.err("dn.max_length", f"путь от цели {fmt_id(k)}: {run_len:.1f} м подряд ДУ {run_dn} > предельных {R.max_length(run_dn)} м (§2.3)")
                if ln is not None:
                    run_dn, run_len = dn, ln["len"]
        # возможное завышение ДУ (предупреждение)
        for ln in self.lines:
            k = ln["key"]
            if ln["dn"] is None or k not in self.expected_flow:
                continue
            dmin = R.min_dn_by_flow(self.expected_flow[k])
            if dmin is not None and dmin < ln["dn"]:
                chain = ln["len"]
                if chain <= R.max_length(dmin):
                    self.warn("dn.not_minimal", f"участок {fmt_id(k)}: ДУ {ln['dn']}, по расходу хватает {dmin} — проверьте предельную длину")

    # --- ограничения

    def own_final_segment(self, ln):
        """Финальный прямой подход к цели внутри своего ОКС: (ключ цели, полигоны, отрезок) или None."""
        for field, first in (("start_node_id", True), ("end_node_id", False)):
            k = ln.get(field)
            if k is None or self.resolve(k)[0] != "target":
                continue
            own = self.inp.own_oks.get(k) or []
            if not own:
                continue
            cs = list(ln["geom"].coords)
            seg = LineString(cs[:2] if first else cs[-2:])
            return k, own, seg
        return None

    def check_clearances(self):
        for ln in self.lines:
            if ln["dn"] is None:
                continue
            g, dn, name = ln["geom"], ln["dn"], fmt_id(ln["key"])
            hw = R.half_width(dn)
            special = ln["p"].get("laying_method") == "special"
            final = self.own_final_segment(ln)
            if final:
                tk, own, seg = final
                tpt = self.inp.targets[tk]["pt"]
                for poly in own:
                    self.check_final_approach(name, tk, tpt, poly, seg, dn)
            own_parts = [o["geom"] for o in final[1]] if final else []
            crossed = []
            for r in self.inp.restrictions_near(g, 15.0):
                rule = R.RESTRICTIONS[r["type"]]
                check = g
                if final and any(r is o["restriction"] for o in final[1]):
                    # свой ОКС: финальный участок освобождён от отступа, но не может пересекать его части вторично
                    check = self.without_segment(g, final[2])
                    if check is None:
                        continue
                if rule["kind"] == "forbidden":
                    need = R.clearance(r["type"], dn) + hw
                elif special and (r["type"], id_key(r["id"])) in ln.get("run_crossed", set()):
                    crossed.append(r)
                    continue
                else:
                    need = rule["clearance"] + hw + rule["own_width"] / 2.0
                d = check.distance(r["geom"])
                if d < need - EPS_CLEAR:
                    what = "пересекает" if d == 0 else f"в {d:.2f} м (нужно ≥ {need:.2f} м от оси)"
                    self.err("clearance." + r["type"], f"участок {name} ({ln['p'].get('laying_method')}) {what} {r['type']} id={r['id']}")
            for p in self.inp.pipes_near(g, 10.0):
                rule = R.EXISTING_HEAT_NETWORK
                need = rule["clearance"] + hw + R.half_width_existing(p["dn"])
                check = g
                for field in ("start_node_id", "end_node_id"):
                    k = ln.get(field)
                    if k in self.roots:
                        npt = self.resolve(k)[1]
                        if p["line"].distance(npt) <= INPUT_TOPO_TOL:
                            # подход к своей точке присоединения: отступ до трубы в радиусе 2·need не проверяем
                            check = check.difference(npt.buffer(2 * need))
                if check.is_empty:
                    continue
                if special and ("heat_network", id_key(p["id"])) in ln.get("run_crossed", set()):
                    continue
                d = check.distance(p["line"])
                if d < need - EPS_CLEAR:
                    what = "пересекает" if d == 0 else f"в {d:.2f} м (нужно ≥ {need:.2f} м от оси)"
                    self.err("clearance.heat_network", f"участок {name} ({ln['p'].get('laying_method')}) {what} "
                                                       f"существующую теплосеть id={p['id']} без спецпрохода")

    def check_final_approach(self, name, tk, tpt, poly, seg, dn):
        """§2.2: финальный прямой участок через ближайшую точку внешнего контура своей части ОКС.
        Нестрогий подход (через другую точку) — предупреждение, если строгий выход действительно закрыт."""
        part = poly["geom"]
        whole = poly["restriction"]["geom"]
        pb = nearest_points(part.exterior, tpt)[0]
        inside = seg.intersection(whole)
        if inside.geom_type != "LineString" or inside.distance(tpt) > INPUT_TOPO_TOL:
            self.err("oks.final_approach", f"участок {name}: финальный отрезок к цели {fmt_id(tk)} пересекает ОКС {poly['id']} "
                                           f"не одним куском (заходит в него повторно)")
            return
        if seg.distance(pb) <= INPUT_TOPO_TOL:
            return
        d = tpt.distance(pb)
        if d == 0:
            return
        u = ((pb.x - tpt.x) / d, (pb.y - tpt.y) / d)
        need_own = R.clearance("oks", dn) + R.half_width(dn)
        others = []
        for r in self.inp.restrictions_near(LineString([(pb.x, pb.y), (pb.x + u[0] * 80, pb.y + u[1] * 80)]), 15.0):
            if r is poly["restriction"] or R.RESTRICTIONS[r["type"]]["kind"] != "forbidden":
                continue
            others.append((r["geom"], R.clearance(r["type"], dn) + R.half_width(dn)))
        # строгий выход свободен, если по лучу есть точка вне зоны отступа своего ОКС,
        # до которой луч не заходит в свой ОКС и не приближается к чужим запретным объектам
        blocked = True
        start = (pb.x + u[0] * 0.01, pb.y + u[1] * 0.01)
        s_ = 0.25
        while s_ <= 80.0:
            pt = Point(pb.x + u[0] * s_, pb.y + u[1] * s_)
            out_seg = LineString([start, (pt.x, pt.y)])
            if out_seg.intersects(whole) or any(out_seg.distance(g) < need - EPS_CLEAR for g, need in others):
                break
            if pt.distance(whole) >= need_own:
                blocked = False
                break
            s_ += 0.25
        if blocked:
            self.warn("oks.final_approach_relaxed", f"участок {name}: к цели {fmt_id(tk)} подход не через ближайшую точку "
                                                    f"контура ОКС {poly['id']} — строгий выход закрыт (DECISIONS №27)")
        else:
            self.err("oks.final_approach", f"участок {name}: финальный подход к цели {fmt_id(tk)} не через ближайшую точку "
                                           f"контура ОКС {poly['id']}, хотя строгий выход свободен (§2.2)")

    @staticmethod
    def without_segment(line, seg):
        rest = line.difference(seg.buffer(1e-6))
        return None if rest.is_empty else rest

    def check_special_runs(self):
        specials = [ln for ln in self.lines if ln["p"].get("laying_method") == "special"]
        for ln in specials:
            if len(ln["geom"].coords) != 2:
                self.err("special.not_straight", f"спецучасток {fmt_id(ln['key'])} не является одним прямым отрезком (§4)")
        # серии спецучастков, соединённых соосно через проходные узлы
        seen = set()
        for ln in specials:
            if ln["key"] in seen or len(ln["geom"].coords) != 2:
                continue
            run = self.collect_run(ln, seen)
            self.check_run(run)

    def collect_run(self, start, seen):
        run = [start]
        seen.add(start["key"])
        frontier = [start]
        while frontier:
            cur = frontier.pop()
            for field in ("start_node_id", "end_node_id"):
                k = cur.get(field)
                if k is None or len(self.adj.get(k, [])) != 2:
                    continue
                for other, _ in self.adj[k]:
                    if other is cur or other["key"] in seen or other["p"].get("laying_method") != "special":
                        continue
                    if len(other["geom"].coords) != 2:
                        continue
                    a = list(cur["geom"].coords)
                    b = list(other["geom"].coords)
                    ang = crossing_angle((a[1][0] - a[0][0], a[1][1] - a[0][1]), (b[1][0] - b[0][0], b[1][1] - b[0][1]))
                    if ang < 0.5:
                        seen.add(other["key"])
                        run.append(other)
                        frontier.append(other)
        return run

    def check_run(self, run):
        pts = [c for ln in run for c in ln["geom"].coords]
        # крайние точки серии — самая удалённая пара
        a, b = max(((p, q) for p in pts for q in pts), key=lambda pq: math.dist(pq[0], pq[1]))
        L = math.dist(a, b)
        if L == 0:
            return
        u = ((b[0] - a[0]) / L, (b[1] - a[1]) / L)
        ext = LineString([(a[0] - SPECIAL_EXT * u[0], a[1] - SPECIAL_EXT * u[1]),
                          (b[0] + SPECIAL_EXT * u[0], b[1] + SPECIAL_EXT * u[1])])
        s_lo, s_hi = SPECIAL_EXT, SPECIAL_EXT + L
        run_line = LineString([a, b])
        dn = max((ln["dn"] or 0) for ln in run)
        hw = R.half_width(dn) if dn in R.DN_TABLE else 0.0
        zones = []   # (s0, s1, k, label)
        candidates = [(r["type"], R.RESTRICTIONS[r["type"]], r["geom"], r["id"], R.RESTRICTIONS[r["type"]]["own_width"] / 2.0)
                      for r in self.inp.restrictions_near(run_line, 10.0) if R.RESTRICTIONS[r["type"]]["kind"] != "forbidden"]
        candidates += [("heat_network", R.EXISTING_HEAT_NETWORK, p["line"], p["id"], R.half_width_existing(p["dn"]))
                       for p in self.inp.pipes_near(run_line, 10.0)]
        # концы серии в узле присоединения к существующей сети: спецпроход через дорогу может в нём заканчиваться
        # (труба или камера внутри полигона дороги, DECISIONS №51)
        root_pts = [self.resolve(k)[1] for k in self.roots]
        end_lo_root = any(Point(a).distance(p) <= self.tol for p in root_pts)
        end_hi_root = any(Point(b).distance(p) <= self.tol for p in root_pts)
        crossed = set()
        for rtype, rule, geom, rid, own_hw in candidates:
            if not run_line.intersects(geom):
                continue
            crossed.add((rtype, id_key(rid)))
            clear_iv = intervals_along(ext, geom.buffer(rule["clearance"] + hw + own_hw))
            for s0, s1 in intervals_along(ext, geom):
                if s1 < s_lo - 1e-6 or s0 > s_hi + 1e-6:
                    continue
                z0, z1 = s0 - rule["margin"], s1 + rule["margin"]
                inside_hi = rule["kind"] == "area" and end_hi_root and s1 >= s_hi - LEN_TOL
                inside_lo = rule["kind"] == "area" and end_lo_root and s0 <= s_lo + LEN_TOL
                if inside_hi:
                    z1 = s_hi
                if inside_lo:
                    z0 = s_lo
                if z0 < s_lo - LEN_TOL or z1 > s_hi + LEN_TOL:
                    self.err("special.extent", f"спецпроход через {rtype} id={rid}: участок должен покрывать "
                                               f"{'полигон' if rule['kind'] == 'area' else 'точку пересечения'} и по {rule['margin']} м с каждой стороны (§4)")
                # зона спецпрохода: запас по таблице 2, а если отступ требует большего — до выхода из зоны отступа
                for c0, c1 in clear_iv:
                    if c0 <= s0 + 1e-6 and c1 >= s1 - 1e-6:
                        z0, z1 = min(z0, c0), max(z1, c1)
                zones.append((max(z0, s_lo), min(z1, s_hi), rule["k"], f"{rtype} id={rid}"))
                if rule["angle"]:
                    for s in [x for x, inside in ((s0, inside_lo), (s1, inside_hi)) if not inside]:
                        cp = ext.interpolate(s)
                        bd = boundary_dir_at(geom, cp)
                        if bd is not None:
                            ang = crossing_angle(u, bd)
                            if ang < rule["angle"] - 1e-6:
                                self.err("special.angle", f"спецпроход через {rtype} id={rid}: угол {ang:.1f}° < {rule['angle']}° (§4)")
        for ln in run:
            ln["run_crossed"] = crossed
        if not zones:
            self.err("special.no_crossing", f"спецучасток(и) {[fmt_id(ln['key']) for ln in run]} не пересекают ограничений со спецпроходом")
            return
        # Kспец каждого куска и смена набора ограничений внутри куска
        for ln in run:
            cs = list(ln["geom"].coords)
            p0, p1 = sorted((ext.project(Point(cs[0])), ext.project(Point(cs[1]))))
            mid = (p0 + p1) / 2
            active = [z for z in zones if z[0] - LEN_TOL <= mid <= z[1] + LEN_TOL]
            k = max((z[2] for z in active), default=None)
            ln["k_expected"] = k if k is not None else 1.0
            if k is None:
                self.err("special.outside_zone", f"спецучасток {fmt_id(ln['key'])} вне зон спецпрохода")
            for z in zones:
                for edge in (z[0], z[1]):
                    if p0 + LEN_TOL < edge < p1 - LEN_TOL:
                        self.warn("special.set_change", f"спецучасток {fmt_id(ln['key'])}: внутри меняется набор ограничений ({z[3]}), "
                                                        f"участок нужно разделить (разъяснение №8)")

    # --- стоимость

    def check_costs_and_summary(self):
        total_pipe = 0.0
        total_len = 0.0
        for ln in self.lines:
            if ln["dn"] is None:
                continue
            k = ln.get("k_expected", 1.0) if ln["p"].get("laying_method") == "special" else 1.0
            exp = ln["len"] * R.price(ln["dn"]) * k
            total_pipe += exp
            total_len += ln["len"]
            got = ln["p"].get("cost")
            if not isinstance(got, (int, float)) or abs(got - exp) > MONEY_TOL + 1e-9 * exp:
                self.err("cost.line", f"участок {fmt_id(ln['key'])}: cost={got!r}, ожидается {exp:.2f} (Kспец={k})")
        chamber_cost = 0.0
        for k, n in self.nodes.items():
            if n["type"] != "heat_chamber":
                continue
            dns = [ln["dn"] for ln, _ in self.adj.get(k, []) if ln["dn"]]
            dns += [p["dn"] for p in self.inp.pipes_near(n["pt"], INPUT_TOPO_TOL) if p["line"].distance(n["pt"]) <= INPUT_TOPO_TOL]
            if not dns:
                continue
            max_dn = max(dns)
            if n["p"].get("diameter") != max_dn:
                self.err("chamber.diameter", f"камера {fmt_id(k)}: diameter={n['p'].get('diameter')!r}, "
                                             f"наибольший ДУ примыкающих участков (вкл. существующие) {max_dn} (§3.2)")
            exp = R.new_chamber_cost(max_dn)
            chamber_cost += exp
            got = n["p"].get("cost")
            if not isinstance(got, (int, float)) or abs(got - exp) > MONEY_TOL:
                self.err("chamber.cost", f"камера {fmt_id(k)}: cost={got!r}, ожидается {exp}")
        tie_ins = sum(len(self.adj.get(k, [])) for k in self.inp.chambers)
        tie_cost = tie_ins * R.TIE_IN_COST
        connected = {k for k in self.adj if self.resolve(k)[0] == "target"
                     and self.component_has_root.get(self.component_of.get(k), False)}
        all_targets = {**self.inp.targets, **self.inp.invalid_targets}
        unconnected = [k for k in all_targets if k not in connected]
        pen = sum(R.penalty(all_targets[k]["flow"]) for k in unconnected)
        construction = total_pipe + chamber_cost + tie_cost
        calculated = construction + pen
        sc = R.score(calculated, total_len)
        self.recomputed = dict(construction_cost=construction, chamber_construction_cost=chamber_cost,
                               existing_chamber_tie_in_count=tie_ins, existing_chamber_tie_in_cost=tie_cost,
                               unconnected_penalty=pen, calculated_cost=calculated, new_network_length=total_len,
                               score=sc, connected=len(connected), unconnected=len(unconnected))
        s = self.summary
        if s is None:
            return
        for field, exp, tol in (("construction_cost", construction, MONEY_TOL), ("chamber_construction_cost", chamber_cost, MONEY_TOL),
                                ("existing_chamber_tie_in_count", tie_ins, 0), ("existing_chamber_tie_in_cost", tie_cost, MONEY_TOL),
                                ("unconnected_penalty", pen, MONEY_TOL), ("calculated_cost", calculated, MONEY_TOL),
                                ("new_network_length", total_len, LEN_TOL), ("score", sc, SCORE_TOL)):
            got = s.get(field)
            if not isinstance(got, (int, float)) or isinstance(got, bool) or abs(got - exp) > tol + 1e-9 * abs(exp):
                self.err("summary." + field, f"{field}={got!r}, пересчёт даёт {exp:.6f}")
        declared = s.get("unconnected_oks_ids")
        if not isinstance(declared, list):
            self.err("summary.unconnected_ids", "unconnected_oks_ids должен быть JSON-массивом")
        else:
            dk = {id_key(v) for v in declared}
            if dk != set(unconnected):
                self.err("summary.unconnected_ids", f"unconnected_oks_ids={declared}, по сети не подключены {[fmt_id(k) for k in unconnected]}")
        if not isinstance(s.get("rank"), int):
            self.err("summary.rank", f"rank={s.get('rank')!r} должен быть целым")


# ---------------------------------------------------------------- файл целиком

def validate(input_path, output_path, node_tol=0.01):
    rep = Report()
    inp = InputData(input_path, rep)
    with open(output_path, encoding="utf-8") as f:
        out = json.load(f)
    if out.get("type") != "FeatureCollection" or not isinstance(out.get("features"), list):
        rep.err(None, "format.collection", "выход должен быть GeoJSON FeatureCollection")
        return rep, {}
    by_variant = defaultdict(list)
    ids = set()
    for i, f in enumerate(out["features"]):
        p = f.get("properties") or {}
        t = p.get("object_type")
        k = id_key(p.get("id"))
        where = f"объект №{i} ({t}, id={p.get('id')!r})"
        if t not in OUT_TYPES:
            rep.err(None, "format.object_type", f"{where}: object_type вне §7.1")
            continue
        if k is None:
            rep.err(None, "format.id", f"{where}: нет id строкой или числом")
            continue
        if k in ids:
            rep.err(None, "format.id_unique", f"{where}: id повторяется")
        ids.add(k)
        if t != "variant_summary" and (k in inp.targets or k in inp.chambers):
            rep.err(None, "format.id_clash", f"{where}: id совпадает с id входного объекта")
        if id_key(p.get("variant_id")) is None:
            rep.err(None, "format.variant_id", f"{where}: нет variant_id")
            continue
        g = f.get("geometry")
        expected_geom = {"heat_network": "LineString", "heat_chamber": "Point", "technical_node": "Point", "variant_summary": None}[t]
        if (g is None and expected_geom is not None) or (g is not None and g.get("type") != expected_geom):
            rep.err(None, "format.geometry", f"{where}: геометрия {g and g.get('type')}, ожидается {expected_geom}")
            continue
        if t == "heat_chamber" and (not isinstance(p.get("diameter"), int) or not isinstance(p.get("cost"), (int, float))):
            rep.err(None, "format.chamber", f"{where}: нужны diameter (целое) и cost")
        by_variant[id_key(p["variant_id"])].append(f)
    results = {}
    for vk, feats in by_variant.items():
        vc = VariantChecker(fmt_id(vk), feats, inp, rep, node_tol)
        vc.run()
        results[fmt_id(vk)] = dict(recomputed=getattr(vc, "recomputed", {}), declared=vc.summary or {})
    # ранжирование
    ranked = sorted(results.items(), key=lambda kv: kv[1]["recomputed"].get("score", math.inf))
    for pos, (vid, r) in enumerate(ranked, start=1):
        if r["declared"].get("rank") != pos:
            same = [v for v, rr in ranked if abs(rr["recomputed"].get("score", 0) - r["recomputed"].get("score", 0)) <= SCORE_TOL]
            if len(same) == 1:
                rep.err(vid, "summary.rank", f"rank={r['declared'].get('rank')!r}, по score место {pos}")
    return rep, results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--json", help="сохранить отчёт в JSON")
    ap.add_argument("--node-tol", type=float, default=0.01, help="допуск совпадения узлов, м (по умолчанию 0,01)")
    ap.add_argument("--max-print", type=int, default=40, help="сколько сообщений печатать на вариант")
    a = ap.parse_args()
    try:
        rep, results = validate(a.input, a.output, a.node_tol)
    except (OSError, ValueError) as e:
        print(f"Не удалось прочитать файлы: {e}", file=sys.stderr)
        return 2
    variants = sorted(results) or [None]
    for vid in variants:
        items = [i for i in rep.items if i["variant"] == vid]
        errs = [i for i in items if i["level"] == "error"]
        r = results.get(vid, {}).get("recomputed", {})
        print(f"=== Вариант {vid}: ошибок {len(errs)}, предупреждений {len(items) - len(errs)}")
        if r:
            print(f"    подключено {r['connected']}, не подключено {r['unconnected']}, длина {r['new_network_length']:.1f} м, "
                  f"стоимость {r['calculated_cost']:,.0f} руб, score {r['score']:.6f}".replace(",", " "))
        for i in items[:a.max_print]:
            print(f"    [{i['level']}] {i['code']}: {i['message']}")
        if len(items) > a.max_print:
            print(f"    … ещё {len(items) - a.max_print}")
    general = [i for i in rep.items if i["variant"] is None]
    for i in general[:a.max_print]:
        print(f"[{i['level']}] {i['code']}: {i['message']}")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(dict(items=rep.items, results=results), f, ensure_ascii=False, indent=2, default=str)
    total = len(rep.errors())
    print(f"ИТОГ: {'OK' if total == 0 else 'НАРУШЕНИЯ'} — ошибок {total}")
    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
