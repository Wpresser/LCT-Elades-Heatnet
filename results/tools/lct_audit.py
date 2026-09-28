#!/usr/bin/env python3
"""Independent auditor for LCT 2026 task 2 outputs (Opus red-team audit).

Written from the official texts only (technical appendix, clarifications, task
PDF). It imports nothing from the audited project and does not read
OFFICIAL_RULES.json: every constant below is transcribed from the appendix and
cited. Usage:

    python lct_audit.py --input DATASET.geojson --output RESULT.geojson \
        [--json report.json] [--md report.md] [--terminal-policy strict|exterior|any]

Exit code 0 = no ERROR findings in any variant, 1 = at least one ERROR.

Severity vocabulary of the report:
  ERROR     - violates an explicit mandatory rule of the appendix / clarifications
  INPUT     - a defect of the input file itself (not counted as an output error)
  AMBIGUOUS - depends on an interpretation the official texts do not settle
  WARNING   - quality / heuristic issue (zigzags, unnecessary nodes, ...)
  INFO      - context
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, MultiPolygon, Point, Polygon, shape
from shapely.ops import linemerge, nearest_points, transform, unary_union

# --------------------------------------------------------------------------
# Official constants (Техническое приложение, таблица 1; §3.2; таблица 2; §6)
# --------------------------------------------------------------------------
# DN: (capacity t/h, max continuous length m, new cost RUB/m, pair width m, height m)
TABLE1 = {
    50: (3.5, 181, 74023, 0.400, 0.125), 65: (8.3, 245, 78631, 0.430, 0.140),
    80: (13.2, 327, 83530, 0.470, 0.160), 100: (22.3, 419, 89748, 0.510, 0.180),
    125: (40.2, 554, 97275, 0.600, 0.225), 150: (65.1, 696, 105507, 0.650, 0.250),
    200: (152.3, 1042, 120275, 0.880, 0.315), 250: (274.9, 1379, 135323, 1.050, 0.400),
    300: (437.4, 1718, 150022, 1.150, 0.450), 400: (943.1, 2477, 190299, 1.370, 0.560),
    500: (1663.4, 3245, 224137, 1.670, 0.710), 600: (2627.7, 4037, 264790, 1.850, 0.800),
    700: (3735.1, 4775, 324298, 2.050, 0.900), 800: (5296.8, 5644, 325996, 2.250, 1.000),
    900: (7165.0, 6518, 327693, 2.450, 1.100), 1000: (9391.8, 7419, 418777, 2.650, 1.200),
    1200: (15012.8, 9288, 428074, 3.100, 1.425), 1400: (22501.9, 11276, 683417, 3.450, 1.600),
}
DNS = sorted(TABLE1)
CAP = {d: v[0] for d, v in TABLE1.items()}
MAXLEN = {d: v[1] for d, v in TABLE1.items()}
PRICE = {d: v[2] for d, v in TABLE1.items()}
WIDTH = {d: v[3] for d, v in TABLE1.items()}


def chamber_cost(dn: int) -> int:  # §3.2
    if 50 <= dn <= 200:
        return 3_000_000
    if 250 <= dn <= 500:
        return 5_000_000
    if 600 <= dn <= 1000:
        return 8_000_000
    if 1200 <= dn <= 1400:
        return 12_000_000
    raise ValueError(f"no chamber cost band for DN {dn}")


TIE_IN_EXISTING = 5_000_000          # §3.2
TIE_DISTANCE = 10.0                  # §2.4
MAX_DEGREE = 4                       # §2.1
MAX_TURN_DEG = 90.0                  # §2.1, Q&A 5
FORBIDDEN = {"oks": None, "park": 1.0, "social_area": 1.0, "prohibited_site": 1.0,
             "water": 1.0, "railway": 1.0}                  # table 2
SPECIAL = {  # type: (clearance m, min angle deg or None, zone kind, zone m, K, own envelope width or None)
    "road": (1.5, 45.0, "polygon", 3.0, 1.60, None),
    "tram_tracks": (1.5, 45.0, "polygon", 3.0, 1.75, None),
    "gas_pipeline": (2.0, None, "point", 2.0, 1.25, 0.40),
    "power_cable": (2.0, None, "point", 2.0, 1.15, 0.20),
    "heat_network": (1.0, None, "point", 2.0, 1.05, "table1"),
}


def oks_clearance(dn: int) -> float:  # table 2
    if dn < 500:
        return 5.0
    if dn < 900:
        return 7.0
    return 9.0


def penalty(flow: float) -> float:  # §6
    return 100_000_000 + 500_000 * flow


def score(c: float, length: float) -> float:  # §6
    return 0.7 * (c / 25_000_000) + 0.3 * (length / 100)


def existing_width(dn) -> tuple[float, str]:
    """Envelope width of an existing heat_network line by table 1."""
    try:
        d = int(dn)
    except (TypeError, ValueError):
        return WIDTH[DNS[0]], "non-numeric diameter; smallest width assumed"
    if d in WIDTH:
        return WIDTH[d], ""
    bigger = [x for x in DNS if x >= d]
    use = bigger[0] if bigger else DNS[-1]
    return WIDTH[use], f"DN {d} not in table 1; width of DN {use} used"


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
TO_UTM = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
NODE_TOL = 0.05      # m, geometric node coincidence
ON_LINE_TOL = 0.05   # m, NEW chamber on an existing line (generated geometry is exact)
EXISTING_HOST_TOL = 0.5  # m, EXISTING chamber vs its lines (input data noise, not a solver output)
OWN_TOL = 1e-3       # m, target on/inside its OKS (WGS84 round trip can put a facade point nm outside)


def idkey(v):
    """Relationship key: numbers compare numerically, strings as strings."""
    if isinstance(v, bool):
        return ("b", v)
    if isinstance(v, (int, float)):
        return ("n", float(v))
    return ("s", str(v))


def fmt_id(v):
    return json.dumps(v, ensure_ascii=False)


def deflection_deg(a, b, c) -> float | None:
    ux, uy = b[0] - a[0], b[1] - a[1]
    vx, vy = c[0] - b[0], c[1] - b[1]
    nu, nv = math.hypot(ux, uy), math.hypot(vx, vy)
    if nu < 1e-9 or nv < 1e-9:
        return None
    cos = max(-1.0, min(1.0, (ux * vx + uy * vy) / (nu * nv)))
    return math.degrees(math.acos(cos))


def acute_angle_deg(u, v) -> float:
    nu, nv = math.hypot(*u), math.hypot(*v)
    if nu < 1e-12 or nv < 1e-12:
        return 0.0
    cos = abs(u[0] * v[0] + u[1] * v[1]) / (nu * nv)
    return math.degrees(math.acos(max(-1.0, min(1.0, cos))))


class Report:
    def __init__(self):
        self.items = []

    def add(self, level, code, msg, **data):
        self.items.append({"level": level, "code": code, "message": msg, **data})

    def count(self, level):
        return sum(1 for i in self.items if i["level"] == level)


# --------------------------------------------------------------------------
# input model
# --------------------------------------------------------------------------
class InputModel:
    def __init__(self, path):
        raw = json.loads(Path(path).read_text(encoding="utf-8"))
        self.raw = raw
        self.problems = []
        self.targets = {}      # key -> dict(id, flow, pt)
        self.chambers = {}     # key -> dict(id, pt)
        self.lines = []        # dict(id, dn, g)
        self.restrictions = []  # dict(id, type, g)
        self.ids = {}
        self.sources = []
        for f in raw.get("features", []):
            p = f.get("properties") or {}
            oid = p.get("id")
            k = idkey(oid)
            if k in self.ids:
                self.problems.append(f"duplicate input id {fmt_id(oid)}")
            self.ids[k] = oid
            ot = p.get("object_type")
            try:
                g = transform(TO_UTM, shape(f["geometry"]))
            except Exception as exc:  # noqa: BLE001
                self.problems.append(f"input id {fmt_id(oid)}: bad geometry {exc}")
                continue
            if ot == "oks_connection_point":
                self.targets[k] = {"id": oid, "flow": float(p.get("flow_tph")), "pt": g}
            elif ot == "heat_chamber":
                self.chambers[k] = {"id": oid, "pt": g}
            elif ot == "heat_network":
                for part in getattr(g, "geoms", [g]):  # a MultiLineString is split, never ignored
                    self.lines.append({"id": oid, "dn": p.get("diameter"), "g": part})
            elif ot == "restriction":
                self.restrictions.append({"id": oid, "type": p.get("restriction_type"), "g": g})
            elif ot == "source":
                self.sources.append({"id": oid, "pt": g})
        xs, ys = [], []
        for f in raw.get("features", []):
            try:
                b = transform(TO_UTM, shape(f["geometry"])).bounds
                xs += [b[0], b[2]]
                ys += [b[1], b[3]]
            except Exception:  # noqa: BLE001
                pass
        self.bbox = (min(xs), min(ys), max(xs), max(ys)) if xs else None
        # existing incidence at existing chambers (§2.1: pass-through = 2)
        self.existing_incidence = {}
        for k, c in self.chambers.items():
            self.existing_incidence[k] = self.incidence_at(c["pt"], EXISTING_HOST_TOL)

    def incidence_at(self, pt, tol=ON_LINE_TOL):
        n = 0
        hosts = []
        end_tol = max(NODE_TOL, tol)
        for ln in self.lines:
            if ln["g"].distance(pt) <= tol:
                cs = list(ln["g"].coords)
                ends = int(Point(cs[0]).distance(pt) <= end_tol) + int(Point(cs[-1]).distance(pt) <= end_tol)
                n += ends if ends else 2
                hosts.append(ln)
        return n, hosts

    def own_polygons(self, target_pt):
        return [r for r in self.restrictions
                if r["type"] == "oks" and r["g"].geom_type in ("Polygon", "MultiPolygon")
                and r["g"].distance(target_pt) <= OWN_TOL]


# --------------------------------------------------------------------------
# terminal policies (§2.2) - see TERMINAL_2_5_10_INDEPENDENT_PROOF.md
# --------------------------------------------------------------------------
def nearest_boundary_points(target, poly_geom, policy):
    """Return list of candidate 'nearest boundary' points under a policy.

    strict   - global nearest point of the whole boundary (all rings, all parts)
    exterior - nearest point of the exterior ring of the part containing target
    any      - None (any boundary point accepted)
    """
    if policy == "any":
        return None
    if policy == "strict":
        b, _ = nearest_points(poly_geom.boundary, target)
        return [b]
    parts = list(poly_geom.geoms) if poly_geom.geom_type == "MultiPolygon" else [poly_geom]
    cands = []
    for part in parts:
        if part.covers(target):
            b, _ = nearest_points(part.exterior, target)
            cands.append(b)
    if not cands:
        b, _ = nearest_points(poly_geom.boundary, target)
        cands.append(b)
    return cands


# --------------------------------------------------------------------------
# main audit of one variant
# --------------------------------------------------------------------------
def audit_variant(inp: InputModel, vid, feats, rep: Report, policy: str):
    res = {"variant_id": vid}
    nets, newch, tech, summaries = [], {}, {}, []
    for f in feats:
        p = f.get("properties") or {}
        ot = p.get("object_type")
        g = None
        if f.get("geometry") is not None:
            try:
                g = transform(TO_UTM, shape(f["geometry"]))
            except Exception as exc:  # noqa: BLE001
                rep.add("ERROR", "GEOM_PARSE", f"id {fmt_id(p.get('id'))}: geometry not parseable: {exc}", variant=vid)
        if ot == "heat_network":
            nets.append((p, g))
        elif ot == "heat_chamber":
            newch[idkey(p.get("id"))] = (p, g)
        elif ot == "technical_node":
            tech[idkey(p.get("id"))] = (p, g)
        elif ot == "variant_summary":
            summaries.append(p)
        else:
            rep.add("ERROR", "OUT_TYPE", f"unsupported output object_type {ot!r} (appendix §7.1)", variant=vid)
    if len(summaries) != 1:
        rep.add("ERROR", "SUMMARY_COUNT", f"expected exactly one variant_summary, got {len(summaries)}", variant=vid)
    summ = summaries[0] if summaries else {}

    # ---- required attributes (§7.2)
    req_net = ["id", "object_type", "variant_id", "start_node_id", "end_node_id", "flow_tph", "diameter",
               "length", "laying_method", "depth_start", "depth_end", "cost"]
    for p, g in nets:
        miss = [k for k in req_net if k not in p]
        if miss:
            rep.add("ERROR", "ATTR_MISSING", f"heat_network {fmt_id(p.get('id'))}: missing {miss}", variant=vid)
        if not isinstance(p.get("diameter"), int) or isinstance(p.get("diameter"), bool):
            rep.add("ERROR", "ATTR_TYPE", f"heat_network {fmt_id(p.get('id'))}: diameter must be integer", variant=vid)
        for k in ("flow_tph", "length", "cost"):
            v = p.get(k)
            if not isinstance(v, (int, float)) or isinstance(v, bool) or not math.isfinite(float(v)):
                rep.add("ERROR", "ATTR_TYPE", f"heat_network {fmt_id(p.get('id'))}: {k} must be a finite number", variant=vid)
        if p.get("laying_method") not in ("base", "special"):
            rep.add("ERROR", "ATTR_VALUE", f"heat_network {fmt_id(p.get('id'))}: laying_method {p.get('laying_method')!r}", variant=vid)
        if p.get("depth_start") is not None or p.get("depth_end") is not None:
            rep.add("INFO", "DEPTH", f"heat_network {fmt_id(p.get('id'))}: non-null depth (2D mode expects null)", variant=vid)
        if g is None or g.geom_type != "LineString":
            rep.add("ERROR", "GEOM_TYPE", f"heat_network {fmt_id(p.get('id'))}: geometry must be LineString", variant=vid)
    for k, (p, g) in newch.items():
        for a in ("diameter", "cost"):
            if a not in p:
                rep.add("ERROR", "ATTR_MISSING", f"heat_chamber {fmt_id(p.get('id'))}: missing {a}", variant=vid)
        if g is None or g.geom_type != "Point":
            rep.add("ERROR", "GEOM_TYPE", f"heat_chamber {fmt_id(p.get('id'))}: geometry must be Point", variant=vid)
    for k, (p, g) in tech.items():
        if g is None or g.geom_type != "Point":
            rep.add("ERROR", "GEOM_TYPE", f"technical_node {fmt_id(p.get('id'))}: geometry must be Point", variant=vid)
    req_sum = ["rank", "construction_cost", "chamber_construction_cost", "existing_chamber_tie_in_count",
               "existing_chamber_tie_in_cost", "unconnected_penalty", "calculated_cost", "new_network_length",
               "score", "unconnected_oks_ids"]
    miss = [k for k in req_sum if k not in summ]
    if summaries and miss:
        rep.add("ERROR", "ATTR_MISSING", f"variant_summary missing {miss}", variant=vid)

    # ---- output id collisions with input ids (reference ambiguity, §7)
    for k, (p, g) in list(newch.items()) + list(tech.items()):
        if k in inp.ids:
            rep.add("ERROR", "ID_COLLISION", f"output node id {fmt_id(p.get('id'))} equals an input id - references become ambiguous (§7)", variant=vid)

    # ---- node table
    nodes = {}   # key -> (kind, point, raw id)
    for k, t in inp.targets.items():
        nodes[k] = ("target", t["pt"], t["id"])
    for k, c in inp.chambers.items():
        nodes[k] = ("existing_chamber", c["pt"], c["id"])
    for k, (p, g) in newch.items():
        nodes[k] = ("new_chamber", g, p.get("id"))
    for k, (p, g) in tech.items():
        nodes[k] = ("technical_node", g, p.get("id"))

    # ---- per segment geometry, references, length, turns
    edges = []
    for p, g in nets:
        if g is None or g.geom_type != "LineString":
            continue
        eid = p.get("id")
        sk, ek = idkey(p.get("start_node_id")), idkey(p.get("end_node_id"))
        cs = list(g.coords)
        ok = True
        for label, k, pt in (("start", sk, Point(cs[0])), ("end", ek, Point(cs[-1]))):
            if k not in nodes:
                rep.add("ERROR", "REF", f"heat_network {fmt_id(eid)}: {label}_node_id {fmt_id(p.get(label + '_node_id'))} is not an allowed node (§7.2)", variant=vid)
                ok = False
                continue
            kind, npt, raw = nodes[k]
            if npt is not None and pt.distance(npt) > NODE_TOL:
                rep.add("ERROR", "ENDPOINT", f"heat_network {fmt_id(eid)}: {label} vertex is {pt.distance(npt):.3f} m from node {fmt_id(raw)} (§7.2)", variant=vid)
            if raw is not None and type(raw) is not type(p.get(label + "_node_id")) and kind in ("target", "existing_chamber"):
                rep.add("ERROR", "ID_TYPE", f"heat_network {fmt_id(eid)}: {label}_node_id type {type(p.get(label + '_node_id')).__name__} differs from input id type {type(raw).__name__} (§7)", variant=vid)
        if sk == ek:
            rep.add("ERROR", "SELF_LOOP", f"heat_network {fmt_id(eid)}: start == end", variant=vid)
        prop_len = p.get("length")
        if isinstance(prop_len, (int, float)) and abs(float(prop_len) - g.length) > 0.01 + 1e-6 * g.length:
            rep.add("ERROR", "LENGTH", f"heat_network {fmt_id(eid)}: length {prop_len} != UTM geometry {g.length:.4f}", variant=vid)
        for i in range(1, len(cs) - 1):
            d = deflection_deg(cs[i - 1], cs[i], cs[i + 1])
            if d is None:
                rep.add("ERROR", "ZERO_SEG", f"heat_network {fmt_id(eid)}: zero-length sub-segment at vertex {i}", variant=vid)
            elif d > MAX_TURN_DEG + 1e-6:
                rep.add("ERROR", "TURN", f"heat_network {fmt_id(eid)}: deflection {d:.3f} deg > 90 at vertex {i} (§2.1)", variant=vid)
        # zigzag heuristic (§2.1 'необоснованные мелкие изломы')
        segl = [math.dist(cs[i], cs[i + 1]) for i in range(len(cs) - 1)]
        for i in range(1, len(cs) - 1):
            d = deflection_deg(cs[i - 1], cs[i], cs[i + 1]) or 0.0
            if d > 5.0 and min(segl[i - 1], segl[i]) < 1.0:
                rep.add("WARNING", "ZIGZAG", f"heat_network {fmt_id(eid)}: {d:.1f} deg turn next to a {min(segl[i-1], segl[i]):.2f} m sub-segment (possible unjustified small break)", variant=vid)
        edges.append({"p": p, "g": g, "id": eid, "s": sk, "e": ek, "ok": ok,
                      "dn": p.get("diameter"), "lay": p.get("laying_method")})

    # ---- degree, cycles, components
    deg = defaultdict(int)
    adj = defaultdict(list)
    for e in edges:
        deg[e["s"]] += 1
        deg[e["e"]] += 1
        adj[e["s"]].append(e)
        adj[e["e"]].append(e)
    parent = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for e in edges:
        a, b = find(e["s"]), find(e["e"])
        if a == b:
            rep.add("ERROR", "CYCLE", f"cycle closed by heat_network {fmt_id(e['id'])} (§2.1)", variant=vid)
        else:
            parent[b] = a

    # tie-in nodes: existing chambers with new segments, new chambers on an existing line
    tie_nodes = {}
    for k, (kind, pt, raw) in nodes.items():
        if deg[k] == 0:
            continue
        if kind == "existing_chamber":
            tie_nodes[k] = "existing"
        elif kind == "new_chamber" and pt is not None and any(ln["g"].distance(pt) <= ON_LINE_TOL for ln in inp.lines):
            tie_nodes[k] = "new"
    comps = defaultdict(set)
    for k in [k for k, d in deg.items() if d > 0]:
        comps[find(k)].add(k)
    for root, members in comps.items():
        ties = [m for m in members if m in tie_nodes]
        if len(ties) != 1:
            names = [fmt_id(nodes[m][2]) for m in ties] if ties else []
            rep.add("ERROR", "COMPONENT_TIEINS",
                    f"network component with nodes {sorted(fmt_id(nodes[m][2]) if m in nodes else str(m) for m in members)[:6]}... has {len(ties)} tie-in nodes {names} (ТЗ §2.2: each part joins the existing network at one point)", variant=vid)

    # node degree rules
    for k, (kind, pt, raw) in nodes.items():
        d = deg[k]
        if d == 0:
            if kind in ("new_chamber", "technical_node"):
                rep.add("ERROR", "ORPHAN", f"{kind} {fmt_id(raw)} has no incident new segment", variant=vid)
            continue
        if kind == "target" and d > 1:
            rep.add("ERROR", "TARGET_DEGREE", f"target {fmt_id(raw)} has degree {d} (a target is a leaf; branching only in chambers §2.1)", variant=vid)
        if kind == "technical_node" and d != 2:
            rep.add("ERROR", "TECH_DEGREE", f"technical_node {fmt_id(raw)} has degree {d} != 2 (§2.1: no branching)", variant=vid)
        if kind == "existing_chamber":
            n_ex, _ = inp.existing_incidence[k]
            if n_ex + d > MAX_DEGREE:
                rep.add("ERROR", "CHAMBER_DEGREE", f"existing chamber {fmt_id(raw)}: {n_ex} existing + {d} new = {n_ex + d} > 4 (§2.1, Q&A 12)", variant=vid)
        if kind == "new_chamber":
            n_ex, hosts = inp.incidence_at(pt)
            if n_ex + d > MAX_DEGREE:
                rep.add("ERROR", "CHAMBER_DEGREE", f"new chamber {fmt_id(raw)}: {n_ex} existing + {d} new = {n_ex + d} > 4 (§2.1)", variant=vid)
            if not hosts and d < 3:
                rep.add("WARNING" if d == 2 else "ERROR", "CHAMBER_ROLE",
                        f"new chamber {fmt_id(raw)} is neither on the existing network nor a branch (degree {d}); a turn needs no chamber (§2.1)", variant=vid)
        if kind in ("target", "technical_node") and d > 2:
            rep.add("ERROR", "BRANCH_OUTSIDE_CHAMBER", f"branching at {kind} {fmt_id(raw)} (§2.1)", variant=vid)

    # ---- 10 m rule (§2.4, Q&A 11)
    new_incident_existing = {k: deg[k] for k in inp.chambers}
    for k, kind in tie_nodes.items():
        if kind != "new":
            continue
        pt = nodes[k][1]
        k_new = deg[k]
        for ck, c in inp.chambers.items():
            dist = pt.distance(c["pt"])
            if dist <= TIE_DISTANCE + 1e-9:
                n_ex, _ = inp.existing_incidence[ck]
                after = n_ex + new_incident_existing.get(ck, 0) + k_new
                if after <= MAX_DEGREE:
                    rep.add("ERROR", "TEN_METRE_RULE",
                            f"new tie-in chamber {fmt_id(nodes[k][2])} is {dist:.2f} m from existing chamber {fmt_id(c['id'])} which would have {after} <= 4 incident segments after connection - the existing chamber must be used (§2.4, Q&A 11)",
                            variant=vid, distance_m=dist)
                else:
                    rep.add("INFO", "TEN_METRE_FULL",
                            f"new tie-in chamber {fmt_id(nodes[k][2])} is {dist:.2f} m from existing chamber {fmt_id(c['id'])}, which would exceed 4 incident segments ({after}); new chamber allowed", variant=vid)

    # ---- orientation, flows, DN rules
    target_flow = {k: t["flow"] for k, t in inp.targets.items()}
    down = {}   # edge id -> (upstream node, downstream node)
    order = []
    seen_nodes = set()
    for k in tie_nodes:
        stack = [(k, None)]
        while stack:
            node, via = stack.pop()
            if node in seen_nodes:
                continue
            seen_nodes.add(node)
            for e in adj[node]:
                if e is via:
                    continue
                other = e["e"] if e["s"] == node else e["s"]
                if id(e) in down:
                    continue
                down[id(e)] = (node, other)
                order.append(e)
                stack.append((other, e))
    children = defaultdict(list)
    for e in order:
        u, v = down[id(e)]
        children[u].append(e)
    flow_calc = {}

    def node_flow(n):
        f = target_flow.get(n, 0.0) if nodes.get(n, ("",))[0] == "target" else 0.0
        for e in children.get(n, []):
            f += edge_flow(e)
        return f

    def edge_flow(e):
        if id(e) in flow_calc:
            return flow_calc[id(e)]
        v = down[id(e)][1]
        flow_calc[id(e)] = node_flow(v)
        return flow_calc[id(e)]
    unoriented = [e for e in edges if id(e) not in down]
    for e in unoriented:
        rep.add("ERROR", "NO_TIEIN_PATH", f"heat_network {fmt_id(e['id'])} is not connected to any tie-in", variant=vid)
    for e in order:
        fc = edge_flow(e)
        e["flow_calc"] = fc
        fp = e["p"].get("flow_tph")
        if isinstance(fp, (int, float)) and abs(float(fp) - fc) > 1e-6 * max(1.0, fc):
            rep.add("ERROR", "FLOW", f"heat_network {fmt_id(e['id'])}: flow_tph {fp} != downstream sum {fc:.6f} (§2.3)", variant=vid)
        dn = e["dn"]
        if dn not in TABLE1:
            rep.add("ERROR", "DN_TABLE", f"heat_network {fmt_id(e['id'])}: DN {dn} not in table 1", variant=vid)
            continue
        if CAP[dn] + 1e-9 < fc:
            rep.add("ERROR", "DN_CAPACITY", f"heat_network {fmt_id(e['id'])}: DN {dn} capacity {CAP[dn]} < flow {fc:.3f}", variant=vid)

    def up_edge(n):
        for e in order:
            if down[id(e)][1] == n:
                return e
        return None
    # monotonic + constant between flow-change nodes
    for e in order:
        u, v = down[id(e)]
        if e["dn"] not in TABLE1:
            continue
        for c in children.get(v, []):
            if c["dn"] in TABLE1 and c["dn"] > e["dn"]:
                rep.add("ERROR", "DN_MONOTONIC", f"DN decreases toward tie-in: {fmt_id(c['id'])} DN {c['dn']} -> {fmt_id(e['id'])} DN {e['dn']} (§2.3)", variant=vid)
        kids = children.get(v, [])
        is_flow_node = nodes.get(v, ("",))[0] == "target" or len(kids) != 1
        if not is_flow_node and kids and kids[0]["dn"] != e["dn"]:
            rep.add("ERROR", "DN_CHANGE_NO_FLOW_CHANGE",
                    f"DN changes {kids[0]['dn']} -> {e['dn']} at {nodes.get(v, ('', None, v))[0]} {fmt_id(nodes.get(v, ('', None, v))[2])} where flow does not change (§2.3: DN kept between flow-change nodes; no DN change only to restart the length counter)", variant=vid)
    # continuous length per path
    leaves = [n for n in seen_nodes if nodes.get(n, ("",))[0] == "target" and deg[n] >= 1]
    worst = []
    for leaf in leaves:
        run_dn, run_len = None, 0.0
        n = leaf
        path = []
        while True:
            e = up_edge(n)
            if e is None:
                break
            path.append(e)
            n = down[id(e)][0]
        for e in path:
            if e["dn"] != run_dn:
                run_dn, run_len = e["dn"], 0.0
            run_len += e["g"].length
            if run_dn in MAXLEN and run_len > MAXLEN[run_dn] + 1e-6:
                rep.add("ERROR", "CONT_LENGTH", f"path from target {fmt_id(nodes[leaf][2])}: continuous DN {run_dn} run reaches {run_len:.2f} m > {MAXLEN[run_dn]} m at {fmt_id(e['id'])} (§2.3)", variant=vid)
                break
        worst.append((fmt_id(nodes[leaf][2]), [(fmt_id(x["id"]), x["dn"]) for x in path]))
    # local minimality (no arbitrary oversizing): lower one flow-chain at a time
    chains = []
    visited_e = set()
    for e in order:
        if id(e) in visited_e:
            continue
        # walk down to the bottom of the chain
        u, v = down[id(e)]
        chain = [e]
        visited_e.add(id(e))
        cur = v
        while nodes.get(cur, ("",))[0] != "target" and len(children.get(cur, [])) == 1:
            nxt = children[cur][0]
            if id(nxt) in visited_e:
                break
            chain.append(nxt)
            visited_e.add(id(nxt))
            cur = down[id(nxt)][1]
        chains.append(chain)

    def paths_ok(dn_of):
        for leaf in leaves:
            n = leaf
            run_dn, run_len = None, 0.0
            prev = None
            while True:
                e = up_edge(n)
                if e is None:
                    break
                d = dn_of(e)
                if prev is not None and d < prev:
                    return False
                if d != run_dn:
                    run_dn, run_len = d, 0.0
                run_len += e["g"].length
                if run_len > MAXLEN[d] + 1e-6:
                    return False
                prev = d
                n = down[id(e)][0]
        return True
    for chain in chains:
        dn = chain[0]["dn"]
        if dn not in TABLE1 or any(c["dn"] != dn for c in chain):
            continue
        smaller = [d for d in DNS if d < dn]
        if not smaller:
            continue
        cand = smaller[-1]
        if any(CAP[cand] + 1e-9 < c.get("flow_calc", 0) for c in chain):
            continue
        ids = {id(c) for c in chain}
        if paths_ok(lambda e, ids=ids, cand=cand: cand if id(e) in ids else e["dn"]):
            rep.add("ERROR", "DN_OVERSIZED", f"chain {[fmt_id(c['id']) for c in chain]} uses DN {dn} but DN {cand} satisfies capacity, monotonicity and continuous length with all other DN fixed (§2.3: arbitrary oversizing forbidden)", variant=vid)

    # ---- restriction checks, special crossings, own-OKS terminal
    special_res = [r for r in inp.restrictions if r["type"] in SPECIAL]
    forb_res = [r for r in inp.restrictions if r["type"] in FORBIDDEN]
    unknown_types = sorted({str(r["type"]) for r in inp.restrictions if r["type"] not in FORBIDDEN and r["type"] not in SPECIAL})
    if unknown_types:
        rep.add("INFO", "UNKNOWN_RESTRICTIONS", f"restriction types outside table 2 ignored (Q&A 9): {unknown_types}", variant=vid)
    existing_as_special = [{"id": ln["id"], "type": "heat_network", "g": ln["g"], "dn": ln["dn"]} for ln in inp.lines]

    # final straight section of every target edge
    final_info = {}
    for e in order:
        u, v = down[id(e)]
        if nodes.get(v, ("",))[0] != "target":
            continue
        cs = list(e["g"].coords)
        if idkey(e["p"].get("end_node_id")) != v:
            cs = cs[::-1]
        # walk back over collinear vertices
        j = len(cs) - 2
        while j > 0:
            d = deflection_deg(cs[j - 1], cs[j], cs[j + 1])
            if d is None or d > 1e-3:
                break
            j -= 1
        final = LineString(cs[j:])
        final_info[id(e)] = (v, final, j, cs)

    min_clear = []
    for e in edges:
        g, dn = e["g"], e["dn"]
        if dn not in TABLE1:
            continue
        half = WIDTH[dn] / 2
        eid = fmt_id(e["id"])
        # which node keys are tie-in endpoints of this edge (for host-line exemption)
        host_ids = set()
        for k in (e["s"], e["e"]):
            if k in tie_nodes:
                tol = EXISTING_HOST_TOL if tie_nodes[k] == "existing" else ON_LINE_TOL
                _, hosts = inp.incidence_at(nodes[k][1], tol)
                host_ids |= {idkey(h["id"]) for h in hosts}
        fin = final_info.get(id(e))
        target_key = fin[0] if fin else None
        own = inp.own_polygons(nodes[target_key][1]) if target_key is not None else []
        own_objs = {id(r) for r in own}  # identity: input ids of restrictions may repeat
        # forbidden restrictions
        for r in forb_res:
            rg = r["g"]
            req = (oks_clearance(dn) if r["type"] == "oks" else FORBIDDEN[r["type"]]) + half
            if id(r) in own_objs:
                v, final, j, cs = fin
                prefix = LineString(cs[: j + 1]) if j >= 1 else None
                if prefix is not None and prefix.length > 1e-9:
                    dpre = prefix.distance(rg)
                    if dpre + 1e-6 < req:
                        rep.add("ERROR", "OWN_OKS_SETBACK", f"heat_network {eid}: part before the final straight section is {dpre:.3f} m from own OKS {fmt_id(r['id'])} < {req:.3f} m (§2.2: setback applies to all but the final straight section)", variant=vid)
                continue
            dist = g.distance(rg)
            min_clear.append((dist - req, eid, r["type"], fmt_id(r["id"])))
            if dist + 1e-6 < req:
                level = "ERROR"
                rep.add(level, "CLEARANCE", f"heat_network {eid} (DN {dn}): {dist:.3f} m to {r['type']} {fmt_id(r['id'])} < required {req:.3f} m (table 2 + §3.1)", variant=vid, actual_m=dist, required_m=req)
        # special restrictions (+ existing heat network)
        crossed = []
        for r in special_res + existing_as_special:
            rg = r["g"]
            clr, ang, zkind, zlen, k, own_w = SPECIAL[r["type"]]
            if own_w == "table1":
                w, note = existing_width(r.get("dn"))
                ow = w / 2
            else:
                ow = (own_w or 0.0) / 2
            req = clr + half + ow
            is_host = r["type"] == "heat_network" and idkey(r["id"]) in host_ids
            inter = g.intersection(rg)
            if not inter.is_empty:
                if is_host:
                    # touching the host line at the tie-in chamber is the connection itself
                    extra = inter.difference(unary_union([nodes[k][1].buffer(NODE_TOL) for k in (e["s"], e["e"]) if k in tie_nodes])) if tie_nodes else inter
                    if not extra.is_empty:
                        rep.add("ERROR", "HOST_LINE_CROSS", f"heat_network {eid} crosses its own tie-in host line {fmt_id(r['id'])} away from the chamber", variant=vid)
                    continue
                crossed.append((r, k))
                if e["lay"] != "special":
                    rep.add("ERROR", "SPECIAL_MISSING", f"heat_network {eid} (laying_method {e['lay']}) crosses {r['type']} {fmt_id(r['id'])} without a special passage (table 2, §4, Q&A 10)", variant=vid)
                continue
            dist = g.distance(rg)
            if is_host:
                # distance to host line near the tie-in: exempt within radius of 2*req (interpretation)
                near = [nodes[k][1] for k in (e["s"], e["e"]) if k in tie_nodes]
                far_part = g.difference(unary_union([p.buffer(2 * req) for p in near])) if near else g
                if not far_part.is_empty and far_part.distance(rg) + 1e-6 < req:
                    rep.add("ERROR", "CLEARANCE", f"heat_network {eid}: {far_part.distance(rg):.3f} m to host heat_network {fmt_id(r['id'])} beyond the tie-in neighbourhood < {req:.3f} m", variant=vid)
                continue
            min_clear.append((dist - req, eid, r["type"], fmt_id(r["id"])))
            if dist + 1e-6 < req:
                # adjacent to a special section of the same restriction? then literal ±2 m / +3 m zone conflict
                level = "ERROR"
                code = "CLEARANCE"
                for other in edges:
                    if other is e or other["lay"] != "special":
                        continue
                    if other["s"] in (e["s"], e["e"]) or other["e"] in (e["s"], e["e"]):
                        if not other["g"].intersection(rg).is_empty:
                            level, code = "AMBIGUOUS", "SPECIAL_ZONE_CLEARANCE"
                rep.add(level, code, f"heat_network {eid} (DN {dn}): {dist:.3f} m to {r['type']} {fmt_id(r['id'])} < required {req:.3f} m (table 2 + §3.1)", variant=vid, actual_m=dist, required_m=req)
        if e["lay"] == "special":
            e["crossed"] = crossed  # checked per special RUN below (pieces joined by technical nodes)
        else:
            exp = g.length * PRICE[dn]
            cp = e["p"].get("cost")
            e["cost_calc"] = exp
            if isinstance(cp, (int, float)) and abs(float(cp) - exp) > 1.0 + 1e-9 * exp:
                rep.add("ERROR", "COST_BASE", f"heat_network {eid}: cost {cp} != L*c = {exp:.4f} (§6)", variant=vid)

    # ---- special passages, checked per run (§4, table 2, Q&A 6/8): a run is a chain of special
    # pieces joined at technical nodes (split where the active set of restrictions changes)
    special_edges = [e for e in edges if e["lay"] == "special" and e["dn"] in TABLE1]
    by_node = defaultdict(list)
    for e in special_edges:
        by_node[e["s"]].append(e)
        by_node[e["e"]].append(e)
    seen_runs = set()
    for start in special_edges:
        if id(start) in seen_runs:
            continue
        run, stack = [], [start]
        while stack:
            cur = stack.pop()
            if id(cur) in seen_runs:
                continue
            seen_runs.add(id(cur))
            run.append(cur)
            for k in (cur["s"], cur["e"]):
                if nodes.get(k, ("",))[0] == "technical_node" and deg[k] == 2:
                    stack += [o for o in by_node[k] if id(o) not in seen_runs]
        joined = unary_union([x["g"] for x in run])
        merged = joined if joined.geom_type == "LineString" else linemerge(joined)
        run_ids = [fmt_id(x["id"]) for x in run]
        if merged.geom_type != "LineString":
            rep.add("ERROR", "SPECIAL_NOT_STRAIGHT", f"special run {run_ids} is not one continuous line (§4)", variant=vid)
            continue
        mc = list(merged.coords)
        chord = LineString([mc[0], mc[-1]])
        if any(chord.distance(Point(c)) > 0.01 for c in mc[1:-1]):
            rep.add("ERROR", "SPECIAL_NOT_STRAIGHT", f"special run {run_ids} bends; a special passage is one straight section (§4, Q&A 6)", variant=vid)
        crossed_run = {}
        for x in run:
            for r, k in x.get("crossed", []):
                crossed_run[id(r)] = (r, k)
        if not crossed_run:
            rep.add("ERROR", "SPECIAL_NOTHING", f"special run {run_ids} crosses no table-2 special restriction", variant=vid)
            continue
        L = merged.length
        direction = (mc[-1][0] - mc[0][0], mc[-1][1] - mc[0][1])
        # end nodes of the run (for the 'ends inside a chamber/target' ambiguity)
        ends = {}
        for x in run:
            for k in (x["s"], x["e"]):
                if nodes.get(k, ("",))[0] != "technical_node" or deg[k] != 2:
                    ends[k] = nodes.get(k, ("", None, k))
        end_kinds = [v[0] for v in ends.values()]
        zones = []
        for r, k in crossed_run.values():
            clr, ang, zkind, zlen, _, _ = SPECIAL[r["type"]]
            rg = r["g"]
            inter = merged.intersection(rg)
            pts = [c for part in getattr(inter, "geoms", [inter]) if not part.is_empty for c in part.coords]
            if not pts:
                continue
            ss = [merged.project(Point(c)) for c in pts]
            if zkind == "polygon" and rg.geom_type in ("Polygon", "MultiPolygon"):
                intervals = [(min(ss) - zlen, max(ss) + zlen)]
            else:
                intervals = [(x0 - zlen, x0 + zlen) for x0 in ss]
            for a0, b0 in intervals:
                zones.append((a0, b0, k, r))
                short0, short1 = -a0 if a0 < 0 else 0.0, b0 - L if b0 > L else 0.0
                for side, short in (("start", short0), ("end", short1)):
                    if short > 0.05:
                        lvl = "AMBIGUOUS" if any(kd in ("new_chamber", "existing_chamber", "target") for kd in end_kinds) else "ERROR"
                        rep.add(lvl, "SPECIAL_ZONE", f"special run {run_ids}: zone of {r['type']} {fmt_id(r['id'])} ({zlen} m beyond the {'boundary' if zkind == 'polygon' else 'crossing'}) is short by {short:.2f} m at the run {side} (table 2)", variant=vid)
            if ang is not None:
                if rg.geom_type in ("Polygon", "MultiPolygon"):
                    rings = [rg.exterior, *rg.interiors] if rg.geom_type == "Polygon" else [x for poly in rg.geoms for x in [poly.exterior, *poly.interiors]]
                else:
                    rings = list(getattr(rg, "geoms", [rg]))
                angles = []
                bnd_pts = merged.intersection(rg.boundary if rg.geom_type in ("Polygon", "MultiPolygon") else rg)
                for pp in [q for q in getattr(bnd_pts, "geoms", [bnd_pts]) if q.geom_type == "Point"]:
                    best = None
                    for ring in rings:
                        rc = list(ring.coords)
                        for i in range(len(rc) - 1):
                            dd = LineString([rc[i], rc[i + 1]]).distance(pp)
                            if best is None or dd < best[0]:
                                best = (dd, (rc[i + 1][0] - rc[i][0], rc[i + 1][1] - rc[i][1]))
                    if best:
                        angles.append(acute_angle_deg(direction, best[1]))
                if angles and min(angles) + 1e-6 < ang:
                    lvl = "ERROR" if max(angles) + 1e-6 < ang else "AMBIGUOUS"
                    rep.add(lvl, "SPECIAL_ANGLE", f"special run {run_ids}: crossing angles {[round(x, 2) for x in angles]} deg vs min {ang} for {r['type']} {fmt_id(r['id'])} (table 2, Q&A 6; entry side ambiguous)", variant=vid)
        # cost per piece with the max K of the zones covering the piece (§6, Q&A 8)
        for x in run:
            xc = list(x["g"].coords)
            s0, s1 = sorted((merged.project(Point(xc[0])), merged.project(Point(xc[-1]))))
            ks = [k for a0, b0, k, _r in zones if min(b0, s1) - max(a0, s0) > 1e-6]
            if not ks:
                ks = [max(k for _r, k in crossed_run.values())]
                rep.add("AMBIGUOUS", "SPECIAL_EXTENSION", f"special piece {fmt_id(x['id'])} lies outside every table-2 zone of its run (special section extended through the clearance zone; K of the run applied)", variant=vid)
            kx = max(ks)
            exp = x["g"].length * PRICE[x["dn"]] * kx
            x["cost_calc"] = exp
            cp = x["p"].get("cost")
            if isinstance(cp, (int, float)) and abs(float(cp) - exp) > 1.0 + 1e-9 * exp:
                rep.add("ERROR", "COST_SPECIAL", f"special heat_network {fmt_id(x['id'])}: cost {cp} != L*c*K = {exp:.2f} (K={kx}) (§6, Q&A 8)", variant=vid)

    # ---- own-OKS final segment rule (§2.2)
    term = {}
    for eidk, (v, final, j, cs) in final_info.items():
        t = inp.targets[v]
        own = inp.own_polygons(t["pt"])
        info = {"target": t["id"], "own_oks": [r["id"] for r in own], "final_len_m": final.length}
        primary = min(own, key=lambda r: r["g"].area) if own else None
        for r in own:
            og = r["g"]
            inside = final.intersection(og)
            outside = final.difference(og)
            info["inside_len_m"] = inside.length
            bd = og.boundary.distance(t["pt"])
            if r is not primary and not og.equals(primary["g"]) and inside.length > bd + 0.05:
                # a neighbouring OKS that also touches the target (shared wall): no exemption beyond
                # its own boundary->target distance
                rep.add("ERROR", "FINAL_TUNNEL", f"final straight section to target {fmt_id(t['id'])} runs {inside.length:.2f} m through neighbouring OKS {fmt_id(r['id'])} (§2.2, table 2)", variant=vid)
            if r is primary and bd <= OWN_TOL and inside.length > 0.05:
                rep.add("ERROR", "FINAL_TUNNEL", f"target {fmt_id(t['id'])} lies on the facade of OKS {fmt_id(r['id'])} but its final section runs {inside.length:.2f} m inside it (§2.2)", variant=vid)
            # outside part must be a single piece adjacent to final start (no re-entry)
            pieces = [pc for pc in getattr(outside, "geoms", [outside]) if pc.length > 1e-6]
            if len(pieces) > 1:
                rep.add("ERROR", "FINAL_REENTRY", f"final straight section to target {fmt_id(t['id'])} leaves and re-enters own OKS {fmt_id(r['id'])} (§2.2: one final straight section from the boundary)", variant=vid)
            for pol in ("strict", "exterior", "any"):
                cands = nearest_boundary_points(t["pt"], og, pol)
                if cands is None:
                    info[pol] = True
                    continue
                info[pol] = any(final.distance(b) <= NODE_TOL for b in cands)
                info[pol + "_nearest_m"] = min(t["pt"].distance(b) for b in cands)
            if not info.get(policy, True):
                rep.add("ERROR" if policy == "strict" else "AMBIGUOUS", "FINAL_NOT_NEAREST",
                        f"final straight section to target {fmt_id(t['id'])} does not pass the nearest boundary point of own OKS {fmt_id(r['id'])} under policy '{policy}' (§2.2)", variant=vid)
            elif not info.get("strict", True):
                rep.add("AMBIGUOUS", "FINAL_NOT_STRICT_NEAREST",
                        f"final straight section to target {fmt_id(t['id'])} passes the nearest boundary only under the relaxed policy '{policy}', not under the literal global-nearest reading (§2.2)", variant=vid)
        term[fmt_id(t["id"])] = info
    res["terminal"] = term

    # ---- new segments must not cross outside common nodes (§2.1)
    for i in range(len(edges)):
        for j2 in range(i + 1, len(edges)):
            a, b = edges[i], edges[j2]
            if not a["g"].intersects(b["g"]):
                continue
            common = {a["s"], a["e"]} & {b["s"], b["e"]}
            inter = a["g"].intersection(b["g"])
            allowed = unary_union([nodes[k][1].buffer(NODE_TOL) for k in common]) if common else None
            rest = inter.difference(allowed) if allowed is not None else inter
            if not rest.is_empty:
                rep.add("ERROR", "NEW_CROSS", f"heat_network {fmt_id(a['id'])} and {fmt_id(b['id'])} intersect outside a common node ({rest.geom_type}) (§2.1)", variant=vid)
    # envelope overlap between new lines (not an explicit rule)
    for i in range(len(edges)):
        for j2 in range(i + 1, len(edges)):
            a, b = edges[i], edges[j2]
            if a["dn"] not in TABLE1 or b["dn"] not in TABLE1:
                continue
            common = {a["s"], a["e"]} & {b["s"], b["e"]}
            ga, gb = a["g"], b["g"]
            if common:
                cut = unary_union([nodes[k][1].buffer(5.0) for k in common])
                ga, gb = ga.difference(cut), gb.difference(cut)
                if ga.is_empty or gb.is_empty:
                    continue
            need = WIDTH[a["dn"]] / 2 + WIDTH[b["dn"]] / 2
            if ga.distance(gb) < need - 1e-6:
                rep.add("WARNING", "NEW_ENVELOPE_OVERLAP", f"envelopes of {fmt_id(a['id'])} and {fmt_id(b['id'])} overlap ({ga.distance(gb):.3f} m < {need:.3f} m) away from shared nodes", variant=vid)

    # ---- chambers: DN / cost (§3.2)
    cham_cost_calc = 0.0
    chamber_rows = []
    for k, (p, g) in newch.items():
        inc = [e["dn"] for e in edges if (e["s"] == k or e["e"] == k) and e["dn"] in TABLE1]
        n_ex, hosts = inp.incidence_at(g) if g is not None else (0, [])
        host_dns = []
        for h in hosts:
            try:
                host_dns.append(int(h["dn"]))
            except (TypeError, ValueError):
                pass
        dn_all = max(inc + host_dns) if (inc or host_dns) else None
        dn_new = max(inc) if inc else None
        pd, pc = p.get("diameter"), p.get("cost")
        nontable = False
        try:
            c_all = chamber_cost(dn_all) if dn_all else None
        except ValueError:
            # an existing host line with a DN outside the §3.2 bands (e.g. 550, 1420): costed by
            # the next band (or the last band above 1400); the appendix does not say - AMBIGUOUS
            bands = [(50, 3_000_000), (250, 5_000_000), (600, 8_000_000), (1200, 12_000_000)]
            c_all = next((c for lo, c in bands if dn_all < lo), 12_000_000) if dn_all else None
            nontable = True
        try:
            c_new = chamber_cost(dn_new) if dn_new else None
        except ValueError:
            c_new = None
        chamber_rows.append({"id": p.get("id"), "role": "tie-in" if hosts else "branch", "degree": deg[k],
                             "existing_slots": n_ex, "diameter": pd, "cost": pc, "dn_incl_existing": dn_all,
                             "cost_incl_existing": c_all, "dn_new_only": dn_new, "cost_new_only": c_new})
        if pd == dn_all and pc == c_all:
            cham_cost_calc += c_all
            if nontable:
                rep.add("AMBIGUOUS", "CHAMBER_NONTABLE_DN", f"new chamber {fmt_id(p.get('id'))}: max incident DN {dn_all} is outside the §3.2 bands; costed by the next band ({c_all})", variant=vid)
        elif pd == dn_new and pc == c_new:
            cham_cost_calc += c_new
            rep.add("AMBIGUOUS", "CHAMBER_DN_BASIS", f"new chamber {fmt_id(p.get('id'))}: DN/cost follow new segments only; §3.2 says 'всех примыкающих участков' (existing host line included?)", variant=vid)
        else:
            cham_cost_calc += c_all or 0
            rep.add("ERROR", "CHAMBER_COST", f"new chamber {fmt_id(p.get('id'))}: diameter {pd}/cost {pc} match neither max incident DN incl. existing ({dn_all}/{c_all}) nor new-only ({dn_new}/{c_new}) (§3.2)", variant=vid)
    res["chambers"] = chamber_rows

    # ---- tie-ins into existing chambers
    tie_count = sum(1 for e in edges for k in (e["s"], e["e"]) if nodes.get(k, ("",))[0] == "existing_chamber")

    # ---- unconnected set
    reached = set(seen_nodes)
    unconnected_calc = [t["id"] for k, t in inp.targets.items() if k not in reached]
    listed = summ.get("unconnected_oks_ids", [])
    if not isinstance(listed, list):
        rep.add("ERROR", "UNCONNECTED_TYPE", "unconnected_oks_ids must be an array", variant=vid)
        listed = []
    for x in listed:
        k = idkey(x)
        if k not in inp.targets:
            rep.add("ERROR", "UNCONNECTED_UNKNOWN", f"unconnected id {fmt_id(x)} is not an input oks_connection_point", variant=vid)
        elif type(x) is not type(inp.targets[k]["id"]):
            rep.add("ERROR", "ID_TYPE", f"unconnected id {fmt_id(x)} type differs from input (§7.2)", variant=vid)
    if {idkey(x) for x in listed} != {idkey(x) for x in unconnected_calc} or len(listed) != len(set(map(idkey, listed))):
        rep.add("ERROR", "UNCONNECTED_SET", f"unconnected_oks_ids {listed} != targets not reached from a tie-in {unconnected_calc}", variant=vid)

    # ---- summary arithmetic
    seg_cost_prop = sum(float(e["p"].get("cost", 0) or 0) for e in edges)
    seg_cost_calc = sum(e.get("cost_calc", 0.0) for e in edges)
    cham_cost_prop = sum(float(p.get("cost", 0) or 0) for p, _ in newch.values())
    pen = sum(penalty(inp.targets[idkey(x)]["flow"]) for x in unconnected_calc)
    length_calc = sum(e["g"].length for e in edges)
    constr_calc = seg_cost_calc + cham_cost_calc + tie_count * TIE_IN_EXISTING
    c_calc = constr_calc + pen
    s_calc = score(c_calc, length_calc)
    recompute = {
        "segment_cost_from_geometry": seg_cost_calc, "segment_cost_from_properties": seg_cost_prop,
        "chamber_construction_cost": cham_cost_calc, "chamber_cost_from_properties": cham_cost_prop,
        "existing_chamber_tie_in_count": tie_count, "existing_chamber_tie_in_cost": tie_count * TIE_IN_EXISTING,
        "construction_cost": constr_calc, "unconnected_penalty": pen, "calculated_cost": c_calc,
        "new_network_length": length_calc, "score": s_calc,
        "unconnected_oks_ids": unconnected_calc,
        "connected": len(inp.targets) - len(unconnected_calc), "targets": len(inp.targets),
        "new_chambers": len(newch), "technical_nodes": len(tech), "segments": len(edges),
        "segments_special": sum(1 for e in edges if e["lay"] == "special"),
        "dn_histogram": dict(sorted(defaultdict(int, {}).items())),
    }
    h = defaultdict(int)
    for e in edges:
        h[e["dn"]] += 1
    recompute["dn_histogram"] = {str(k): v for k, v in sorted(h.items(), key=lambda x: str(x[0]))}
    res["recomputed"] = recompute
    res["claimed"] = {k: summ.get(k) for k in req_sum}
    tol_money = 1.0
    checks = [("construction_cost", constr_calc, tol_money), ("chamber_construction_cost", cham_cost_calc, tol_money),
              ("existing_chamber_tie_in_count", tie_count, 0), ("existing_chamber_tie_in_cost", tie_count * TIE_IN_EXISTING, tol_money),
              ("unconnected_penalty", pen, tol_money), ("calculated_cost", c_calc, tol_money),
              ("new_network_length", length_calc, 0.01), ("score", s_calc, 1e-6)]
    deltas = {}
    for k, v, tol in checks:
        got = summ.get(k)
        if not isinstance(got, (int, float)) or isinstance(got, bool):
            continue
        deltas[k] = float(got) - v
        if abs(float(got) - v) > tol:
            rep.add("ERROR", "SUMMARY", f"summary {k} = {got} but recomputed {v} (delta {float(got) - v:.6g}) (§6)", variant=vid)
    # internal consistency of the summary itself
    if all(isinstance(summ.get(k), (int, float)) for k in ("calculated_cost", "new_network_length", "score")):
        s_self = score(float(summ["calculated_cost"]), float(summ["new_network_length"]))
        if abs(s_self - float(summ["score"])) > 1e-6:
            rep.add("ERROR", "SUMMARY_SCORE_FORMULA", f"summary score {summ['score']} != formula from its own C and L {s_self}", variant=vid)
    res["summary_minus_recomputed"] = deltas
    min_clear.sort()
    res["tightest_clearances"] = [{"margin_m": round(m, 4), "segment": s, "type": t, "restriction": r} for m, s, t, r in min_clear[:10]]
    # excessive detour heuristic (§2.1)
    if inp.bbox:
        x0, y0, x1, y1 = inp.bbox
        for e in edges:
            b = e["g"].bounds
            if b[0] < x0 - 100 or b[1] < y0 - 100 or b[2] > x1 + 100 or b[3] > y1 + 100:
                rep.add("WARNING", "OUTSIDE_EXTENT", f"heat_network {fmt_id(e['id'])} leaves the input extent by >100 m (§2.1)", variant=vid)
    return res


def run(input_path, output_path, policy="strict"):
    inp = InputModel(input_path)
    rep = Report()
    for pr in inp.problems:
        rep.add("INPUT", "INPUT", pr)  # a defect of the INPUT file, not of the audited output
    out = json.loads(Path(output_path).read_text(encoding="utf-8"))
    if out.get("type") != "FeatureCollection":
        rep.add("ERROR", "OUT_ROOT", "output is not a FeatureCollection (§7)")
    variants = defaultdict(list)
    seen_ids = {}
    for f in out.get("features", []):
        p = f.get("properties") or {}
        if "variant_id" not in p:
            rep.add("ERROR", "ATTR_MISSING", f"feature {fmt_id(p.get('id'))} has no variant_id (§7.2)")
            continue
        key = idkey(p.get("id"))
        if key in seen_ids:
            rep.add("ERROR", "DUP_ID", f"duplicate output id {fmt_id(p.get('id'))} (§7.2)")
        seen_ids[key] = True
        variants[json.dumps(p["variant_id"])].append(f)
    if len(variants) > 3:
        rep.add("ERROR", "VARIANTS", f"{len(variants)} variants > 3 per mode (§6)")
    results = []
    for vk, feats in variants.items():
        results.append(audit_variant(inp, json.loads(vk), feats, rep, policy))
    # ranks (§7.2: rank 1 = smallest score)
    ranked = []
    for f in out.get("features", []):
        p = f.get("properties") or {}
        if p.get("object_type") == "variant_summary":
            ranked.append((p.get("score"), p.get("rank"), p.get("variant_id")))
    try:
        ordered = sorted(ranked, key=lambda x: float(x[0]))
        for i, (s, r, v) in enumerate(ordered, 1):
            if r != i:
                rep.add("ERROR", "RANK", f"variant {fmt_id(v)} rank {r} but position by score is {i} (§7.2)")
    except (TypeError, ValueError):
        rep.add("ERROR", "RANK", "rank/score not comparable")
    return {"input": str(input_path), "output": str(output_path), "terminal_policy": policy,
            "errors": rep.count("ERROR"), "ambiguous": rep.count("AMBIGUOUS"), "input_problems": rep.count("INPUT"),
            "warnings": rep.count("WARNING"), "variants": results, "items": rep.items}


def to_markdown(r):
    lines = [f"# Independent audit: `{Path(r['output']).name}`", "",
             f"- input: `{Path(r['input']).name}`", f"- terminal policy: `{r['terminal_policy']}`",
             f"- ERROR: **{r['errors']}**, AMBIGUOUS: {r['ambiguous']}, WARNING: {r['warnings']}", ""]
    for v in r["variants"]:
        rc = v["recomputed"]
        lines += [f"## Variant {v['variant_id']}", "",
                  f"| metric | claimed | recomputed |", "|---|---:|---:|"]
        for k in ("construction_cost", "chamber_construction_cost", "existing_chamber_tie_in_count",
                  "existing_chamber_tie_in_cost", "unconnected_penalty", "calculated_cost", "new_network_length", "score"):
            lines.append(f"| {k} | {v['claimed'].get(k)} | {rc[k]} |")
        lines.append(f"| unconnected_oks_ids | {v['claimed'].get('unconnected_oks_ids')} | {rc['unconnected_oks_ids']} |")
        lines.append(f"| coverage | | {rc['connected']}/{rc['targets']} |")
        lines += ["", f"segments {rc['segments']} (special {rc['segments_special']}), new chambers {rc['new_chambers']}, technical nodes {rc['technical_nodes']}, DN histogram {rc['dn_histogram']}", ""]
    lines += ["## Findings", "", "| level | code | variant | message |", "|---|---|---|---|"]
    order = {"ERROR": 0, "AMBIGUOUS": 1, "INPUT": 2, "WARNING": 3, "INFO": 4}
    for it in sorted(r["items"], key=lambda i: order.get(i["level"], 9)):
        lines.append(f"| {it['level']} | {it['code']} | {it.get('variant', '')} | {it['message'].replace('|', '/')} |")
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--json")
    ap.add_argument("--md")
    ap.add_argument("--terminal-policy", default="strict", choices=["strict", "exterior", "any"])
    a = ap.parse_args()
    r = run(a.input, a.output, a.terminal_policy)
    if a.json:
        Path(a.json).write_text(json.dumps(r, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    if a.md:
        Path(a.md).write_text(to_markdown(r), encoding="utf-8")
    for v in r["variants"]:
        rc = v["recomputed"]
        print(f"variant {v['variant_id']}: coverage {rc['connected']}/{rc['targets']} unconnected {rc['unconnected_oks_ids']} "
              f"C={rc['calculated_cost']:.6f} L={rc['new_network_length']:.10f} S={rc['score']:.12f}")
    for it in r["items"]:
        if it["level"] in ("ERROR", "AMBIGUOUS"):
            print(f"{it['level']:9s} {it['code']:24s} {it['message']}")
    print(f"ERROR {r['errors']}  AMBIGUOUS {r['ambiguous']}  WARNING {r['warnings']}")
    return 1 if r["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
