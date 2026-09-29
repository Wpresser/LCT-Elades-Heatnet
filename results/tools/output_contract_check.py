#!/usr/bin/env python3
"""Hostile §7 output-contract checker (read-only). Usage: contract_check.py INPUT OUTPUT"""
import json, sys, math, re
from collections import defaultdict, Counter

try:
    from pyproj import Transformer
    TR = Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True)
except Exception:
    TR = None

inp_path, out_path = sys.argv[1], sys.argv[2]
raw = open(out_path, encoding="utf-8").read()
inp = json.load(open(inp_path, encoding="utf-8"))
out = json.loads(raw)

TABLE = [(50,3.5,181,74023),(65,8.3,245,78631),(80,13.2,327,83530),(100,22.3,419,89748),(125,40.2,554,97275),
         (150,65.1,696,105507),(200,152.3,1042,120275),(250,274.9,1379,135323),(300,437.4,1718,150022),
         (400,943.1,2477,190299),(500,1663.4,3245,224137),(600,2627.7,4037,264790),(700,3735.1,4775,324298),
         (800,5296.8,5644,325996),(900,7165.0,6518,327693),(1000,9391.8,7419,418777),(1200,15012.8,9288,428074),
         (1400,22501.9,11276,683417)]
DN = {r[0]: r for r in TABLE}
def chamber_cost(dn):
    if 50 <= dn <= 200: return 3e6
    if 250 <= dn <= 500: return 5e6
    if 600 <= dn <= 1000: return 8e6
    if 1200 <= dn <= 1400: return 12e6
    return None

issues = []
def err(code, msg): issues.append(("ERROR", code, msg))
def warn(code, msg): issues.append(("WARN", code, msg))
def note(code, msg): issues.append(("NOTE", code, msg))

def is_num(x): return isinstance(x, (int, float)) and not isinstance(x, bool)
def is_int(x): return isinstance(x, int) and not isinstance(x, bool)
def is_id(x): return (isinstance(x, str)) or is_num(x)

# ---------- top-level
if out.get("type") != "FeatureCollection": err("TOP", "type != FeatureCollection")
extra_top = set(out) - {"type", "features"}
if extra_top: note("TOP", f"extra top-level members {extra_top}")
if "crs" not in out: note("CRS", "no crs member (RFC7946 default WGS84 lon/lat) — acceptable")

# raw number formatting
exp_nums = re.findall(r'"([a-z_]+)":(-?\d+\.\d+E-?\d+)', raw)
if exp_nums:
    c = Counter(k for k, _ in exp_nums)
    note("NUMFMT", f"{len(exp_nums)} values in exponent notation, by field: {dict(c)}; e.g. {exp_nums[:3]}")
for tok in ("NaN", "Infinity"):
    if re.search(r'[:\[,]\s*"?-?' + tok, raw): err("NUMFMT", f"{tok} present")

# ---------- input index
in_ids = {}
in_by_type = defaultdict(dict)
for f in inp["features"]:
    p = f["properties"]
    in_ids.setdefault(p["id"], []).append(p["object_type"])
    in_by_type[p["object_type"]][p["id"]] = f
oks = in_by_type["oks_connection_point"]
exch = in_by_type["heat_chamber"]

REQ = {
    "heat_network": {"id","object_type","variant_id","start_node_id","end_node_id","flow_tph","diameter","length","laying_method","depth_start","depth_end","cost"},
    "heat_chamber": {"id","object_type","variant_id","diameter","cost"},
    "technical_node": {"id","object_type","variant_id"},
    "variant_summary": {"id","object_type","variant_id","rank","construction_cost","chamber_construction_cost","existing_chamber_tie_in_count","existing_chamber_tie_in_cost","unconnected_penalty","calculated_cost","new_network_length","score","unconnected_oks_ids"},
}
GEOM = {"heat_network": "LineString", "heat_chamber": "Point", "technical_node": "Point", "variant_summary": None}

feats = out["features"]
seen_ids = Counter()
by_var = defaultdict(lambda: defaultdict(list))
extras = defaultdict(Counter)
for i, f in enumerate(feats):
    if f.get("type") != "Feature": err("FEAT", f"#{i} type {f.get('type')}")
    if set(f) - {"type","properties","geometry"}: note("FEAT", f"#{i} extra members {set(f)-{'type','properties','geometry'}}")
    p = f.get("properties") or {}
    ot = p.get("object_type")
    if ot not in REQ:
        err("OTYPE", f"#{i} unknown object_type {ot}"); continue
    miss = REQ[ot] - set(p)
    if miss: err("MISSING", f"{p.get('id')} ({ot}) missing {miss}")
    for k in set(p) - REQ[ot]: extras[ot][k] += 1
    g = f.get("geometry")
    if GEOM[ot] is None:
        if g is not None: err("GEOM", f"{p.get('id')} summary geometry not null")
    else:
        if not g or g.get("type") != GEOM[ot]: err("GEOM", f"{p.get('id')} geometry {g and g.get('type')} != {GEOM[ot]}")
        else:
            cs = [g["coordinates"]] if GEOM[ot] == "Point" else g["coordinates"]
            for c in cs:
                if len(c) != 2: err("GEOM", f"{p.get('id')} coord dim {len(c)}")
                if not (36 < c[0] < 39 and 54 < c[1] < 57): err("GEOM", f"{p.get('id')} coord order/range suspicious {c}")
    if not is_id(p.get("id")): err("TYPE", f"id type {type(p.get('id'))}")
    if not is_id(p.get("variant_id")): err("TYPE", f"{p.get('id')} variant_id type")
    seen_ids[p.get("id")] += 1
    by_var[p.get("variant_id")][ot].append(f)

for k, n in seen_ids.items():
    if n > 1: err("DUPID", f"id {k!r} appears {n} times")
    if k in in_ids: err("IDCLASH", f"output id {k!r} equals input id of {in_ids[k]}")
for ot, c in extras.items():
    note("EXTRA", f"{ot}: extra properties {dict(c)} (allowed by §7 but visible)")

# ---------- per-variant semantics
summ_rows = []
for vid, groups in by_var.items():
    lines = groups["heat_network"]; chs = groups["heat_chamber"]; tns = groups["technical_node"]; sm = groups["variant_summary"]
    if len(sm) != 1: err("SUMMARY", f"variant {vid}: {len(sm)} summaries"); continue
    s = sm[0]["properties"]
    # node registry
    nodes = {}
    for f in chs: nodes[f["properties"]["id"]] = ("new_ch", tuple(f["geometry"]["coordinates"]), f)
    for f in tns: nodes[f["properties"]["id"]] = ("tn", tuple(f["geometry"]["coordinates"]), f)
    def resolve(nid):
        if nid in nodes: return nodes[nid]
        if nid in oks: return ("oks", tuple(oks[nid]["geometry"]["coordinates"]), oks[nid])
        if nid in exch: return ("ex_ch", tuple(exch[nid]["geometry"]["coordinates"]), exch[nid])
        # string/number confusion
        alt = str(nid)
        for d in (oks, exch):
            for k in d:
                if str(k) == alt: err("REFTYPE", f"{vid}: node ref {nid!r} matches input id {k!r} only after type coercion")
        return None
    # other-variant reference
    all_other = {fid for v2, gg in by_var.items() if v2 != vid for ot2 in ("heat_chamber","technical_node") for fid in (x["properties"]["id"] for x in gg[ot2])}
    adj = defaultdict(list)
    tot_len = 0.0; tot_cost = 0.0
    for f in lines:
        p = f["properties"]; cs = f["geometry"]["coordinates"]
        for k in ("flow_tph","length","cost"):
            if not is_num(p.get(k)): err("TYPE", f"{p['id']} {k} not number: {p.get(k)!r}")
        if not is_int(p.get("diameter")): err("TYPE", f"{p['id']} diameter not integer: {p.get('diameter')!r}")
        if p.get("laying_method") not in ("base","special"): err("VALUE", f"{p['id']} laying_method {p.get('laying_method')}")
        if p.get("depth_start") is not None or p.get("depth_end") is not None: err("VALUE", f"{p['id']} depth not null in 2D")
        if p.get("diameter") not in DN: err("VALUE", f"{p['id']} DN {p.get('diameter')} not in table 1")
        for end, nid, c in (("start", p["start_node_id"], cs[0]), ("end", p["end_node_id"], cs[-1])):
            if nid in all_other and nid not in nodes: err("XVAR", f"{p['id']} references node {nid} of another variant")
            r = resolve(nid)
            if r is None: err("DANGLING", f"{p['id']} {end}_node_id {nid!r} unresolved"); continue
            if tuple(c) != r[1]:
                d = math.hypot((c[0]-r[1][0])*62800, (c[1]-r[1][1])*111300)
                (err if d > 0.01 else warn)("ENDPT", f"{p['id']} {end} coord differs from node {nid} by {d:.4f} m")
            adj[nid].append((p["id"], f))
        # length check
        if TR:
            xy = [TR.transform(x, y) for x, y in cs]
            L = sum(math.dist(xy[i], xy[i+1]) for i in range(len(xy)-1))
            if abs(L - p["length"]) > 1e-3: err("LEN", f"{p['id']} length {p['length']:.4f} vs UTM {L:.4f}")
            # micro vertices
            for i in range(len(xy)-1):
                seg = math.dist(xy[i], xy[i+1])
                if seg < 0.05: warn("MICRO", f"{p['id']} sub-segment {seg*100:.1f} cm at vertex {i}")
            # turn angles
            for i in range(1, len(xy)-1):
                a = (xy[i][0]-xy[i-1][0], xy[i][1]-xy[i-1][1]); b = (xy[i+1][0]-xy[i][0], xy[i+1][1]-xy[i][1])
                na, nb = math.hypot(*a), math.hypot(*b)
                if na < 1e-9 or nb < 1e-9: err("GEOM", f"{p['id']} duplicate vertex {i}"); continue
                cosv = max(-1, min(1, (a[0]*b[0]+a[1]*b[1])/(na*nb)))
                ang = math.degrees(math.acos(cosv))
                if ang > 90 + 1e-6: err("ANGLE", f"{p['id']} turn {ang:.2f}° at vertex {i} > 90°")
        # cost check
        k = 1.0
        if p.get("laying_method") == "special":
            k = p.get("diag_k_spec", None)
            if k is None: warn("KSPEC", f"{p['id']} special without diag_k_spec, cost cannot be verified from mandatory fields")
            k = k or 1.0
        exp = p["length"] * DN[p["diameter"]][3] * k if p["diameter"] in DN else None
        if exp is not None and abs(exp - p["cost"]) > 0.01: err("COST", f"{p['id']} cost {p['cost']} vs L*c*K {exp}")
        tot_len += p["length"]; tot_cost += p["cost"]
    # chambers
    ch_cost = 0.0
    for f in chs:
        p = f["properties"]
        if not is_int(p.get("diameter")): err("TYPE", f"{p['id']} chamber diameter not integer {p.get('diameter')!r}")
        if not is_num(p.get("cost")): err("TYPE", f"{p['id']} chamber cost not number")
        adjdn = [x[1]["properties"]["diameter"] for x in adj[p["id"]]]
        mx = max(adjdn) if adjdn else None
        if mx is not None and p["diameter"] < mx: err("CHDN", f"{p['id']} diameter {p['diameter']} < max adjoining new DN {mx}")
        if chamber_cost(p["diameter"]) is not None and chamber_cost(p["diameter"]) != p["cost"]:
            err("CHCOST", f"{p['id']} cost {p['cost']} vs table for DN {p['diameter']}")
        if not adj[p["id"]]: err("ORPHAN", f"{p['id']} chamber has no adjoining new line")
        ch_cost += p["cost"]
    for f in tns:
        n = len(adj[f["properties"]["id"]])
        if n != 2: err("TN", f"{f['properties']['id']} technical_node degree {n} (must be 2, no branching)")
        else:
            a, b = [x[1]["properties"] for x in adj[f["properties"]["id"]]]
            if (a["diameter"], a["laying_method"]) == (b["diameter"], b["laying_method"]) and a.get("diag_k_spec") == b.get("diag_k_spec"):
                warn("TN", f"{f['properties']['id']}: both sides identical params (unjustified technical node)")
    # tie-ins in existing chambers
    tie = sum(len(v) for k, v in adj.items() if k in exch and k not in nodes)
    # summary checks
    for k in ("construction_cost","chamber_construction_cost","existing_chamber_tie_in_cost","unconnected_penalty","calculated_cost","new_network_length","score"):
        if not is_num(s.get(k)): err("TYPE", f"{vid} summary {k} not number {s.get(k)!r}")
    for k in ("rank","existing_chamber_tie_in_count"):
        if not is_int(s.get(k)): err("TYPE", f"{vid} summary {k} not integer {s.get(k)!r}")
    if not isinstance(s.get("unconnected_oks_ids"), list): err("TYPE", f"{vid} unconnected_oks_ids not array")
    connected = {p for p in oks if p in adj}
    unc = set(oks) - connected
    if set(s["unconnected_oks_ids"]) != unc: err("UNC", f"{vid} unconnected list {s['unconnected_oks_ids']} vs graph {sorted(unc)}")
    for u in s["unconnected_oks_ids"]:
        if u not in oks: err("UNCTYPE", f"{vid} unconnected id {u!r} not an input oks id (type preserved?)")
    pen = sum(100e6 + 500e3 * oks[u]["properties"]["flow_tph"] for u in unc)
    chk = [("chamber_construction_cost", ch_cost), ("existing_chamber_tie_in_count", tie), ("existing_chamber_tie_in_cost", tie*5e6),
           ("unconnected_penalty", pen), ("construction_cost", tot_cost + ch_cost + tie*5e6),
           ("calculated_cost", tot_cost + ch_cost + tie*5e6 + pen), ("new_network_length", tot_len)]
    for k, v in chk:
        if abs(s[k] - v) > 0.01: err("SUMMARY", f"{vid} {k} {s[k]} vs recomputed {v}")
    S = 0.7 * s["calculated_cost"] / 25e6 + 0.3 * s["new_network_length"] / 100
    if abs(S - s["score"]) > 1e-9: err("SCORE", f"{vid} score {s['score']} vs {S}")
    summ_rows.append((vid, s["rank"], s["score"]))
    # ---- flow / DN tree
    # build undirected graph of nodes via lines
    g = defaultdict(list)
    for f in lines:
        p = f["properties"]; g[p["start_node_id"]].append((p["end_node_id"], f)); g[p["end_node_id"]].append((p["start_node_id"], f))
    roots = [nid for nid in g if (nid in exch and nid not in nodes) or (nid in nodes and nodes[nid][2]["properties"].get("diag_tie_in"))]
    # roots via geometry: new chamber lying on existing network -> tie-in; approximate with diag flag
    seen = set(); parent_line = {}
    comp_roots = 0
    for r in roots:
        if r in seen: continue
        comp_roots += 1
        stack = [r]; seen.add(r); order = []
        while stack:
            u = stack.pop(); order.append(u)
            for v, f in g[u]:
                if v in seen:
                    if parent_line.get(u) is not f and parent_line.get(v) is not f and f["properties"]["id"] not in {pl["properties"]["id"] for pl in parent_line.values()}:
                        err("CYCLE", f"{vid}: cycle or second root reached via {f['properties']['id']}")
                    continue
                seen.add(v); parent_line[v] = f; stack.append(v)
        # downstream flow
        down = {}
        for u in reversed(order):
            fl = oks[u]["properties"]["flow_tph"] if u in oks else 0.0
            for v, f in g[u]:
                if parent_line.get(v) is f and v != r and parent_line.get(u) is not f:
                    fl += down[v]
            down[u] = fl
        for u in order:
            if u == r: continue
            f = parent_line[u]; p = f["properties"]
            if abs(p["flow_tph"] - down[u]) > 0.005: err("FLOW", f"{vid} {p['id']} flow {p['flow_tph']} vs downstream sum {down[u]:.4f}")
            if abs(p["flow_tph"] - down[u]) > 1e-9: note("FLOWROUND", f"{vid} {p['id']} flow {p['flow_tph']} vs exact {down[u]!r}")
            mn = min(r_[0] for r_ in TABLE if r_[1] >= down[u])
            if p["diameter"] < mn: err("DNCAP", f"{vid} {p['id']} DN {p['diameter']} below capacity DN {mn}")
            # DN monotone towards root
            pv = [x for x, ff in g[u] if ff is f][0]
            if pv in parent_line:
                pp = parent_line[pv]["properties"]
                if pp["diameter"] < p["diameter"]: err("DNMONO", f"{vid} DN decreases towards root: {p['id']} {p['diameter']} -> {pp['id']} {pp['diameter']}")
    unreached = [n for n in g if n not in seen]
    if unreached: err("ROOTLESS", f"{vid}: nodes not reachable from any tie-in root: {unreached[:5]}")
    # adjacency <= 4 incl. existing slots
    for nid, lst in adj.items():
        if nid in oks:
            if len(lst) != 1: err("OKSDEG", f"{vid} oks {nid} has {len(lst)} lines")
            continue
        ex_slots = 0
        if nid in nodes and nodes[nid][2]["properties"].get("diag_tie_in"): ex_slots = 2
        if nid in exch and nid not in nodes:
            # count existing lines ending/passing
            cx = exch[nid]["geometry"]["coordinates"]
            for hf in in_by_type["heat_network"].values():
                hc = hf["geometry"]["coordinates"]
                if list(hc[0]) == list(cx) or list(hc[-1]) == list(cx): ex_slots += 1
        if len(lst) + ex_slots > 4: err("DEG4", f"{vid} node {nid}: {len(lst)} new + {ex_slots} existing > 4")
    # branching only in chambers: oks with >1, tn with >2 already; check branching at oks/tn
    for nid, lst in adj.items():
        if len(lst) >= 3 and not (nid in nodes and nodes[nid][0] == "new_ch") and not (nid in exch):
            err("BRANCH", f"{vid} branching at non-chamber node {nid}")

# rank order
summ_rows.sort(key=lambda r: r[2])
for i, (vid, rk, sc) in enumerate(summ_rows, 1):
    if rk != i: err("RANK", f"{vid} rank {rk} but position by score {i}")

for lvl, code, msg in issues:
    print(f"{lvl:5} {code:10} {msg}")
print("TOTAL", Counter(l for l, _, _ in issues))
sys.exit(1 if any(level == "ERROR" for level, _, _ in issues) else 0)
