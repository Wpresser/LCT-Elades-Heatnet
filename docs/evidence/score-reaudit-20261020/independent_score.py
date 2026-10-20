"""Fresh audit: no imports from service, validator, audit scripts or FINAL_METRICS.
Prices/capacities/dimensions are read from the original DOCX tables, geometry
is reprojected from coordinates, connectivity/flows are derived from the graph.
"""
from pathlib import Path
import json, math, hashlib, zipfile, xml.etree.ElementTree as E
from collections import defaultdict, Counter, deque
from shapely.geometry import shape, Point, LineString, Polygon
from shapely.ops import transform, nearest_points, unary_union
from pyproj import Transformer, Geod

root=Path(__file__).resolve().parent
repo=root.parents[2]
fw=Transformer.from_crs(4326,32637,always_xy=True).transform
geod=Geod(ellps='WGS84')
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
docpath=repo/'task/sources/Техническое  приложение ЛЦТ.docx'
xml=E.fromstring(zipfile.ZipFile(docpath).read('word/document.xml'))
tables=[[[ ''.join(t.text or '' for t in c.iter(W+'t')) for c in row.findall(W+'tc')] for row in tab.findall(W+'tr')] for tab in xml.findall('.//'+W+'tbl')]
def number(s):return float(s.replace('\xa0','').replace(' ','').replace(',','.'))
params={int(row[0]):dict(zip(['capacity','max_length','price','width','height'],map(number,row[1:]))) for row in tables[1][1:]}
chamber_bands=[]
for row in tables[2][1:]:
    low,high=map(int,row[0].replace('—','-').replace('–','-').split('-'))
    chamber_bands.append((low,high,number(row[1])))
source_text='\n'.join(''.join(t.text or '' for t in p.iter(W+'t')) for p in xml.findall('.//'+W+'p'))
assert '25 000 000' in source_text and '0,7' in source_text and '0,3' in source_text
assert 'пару подающего и обратного трубопроводов' in source_text
(root/'original-docx-extracted.txt').write_text(source_text,encoding='utf-8')
(root/'original-table1.json').write_text(json.dumps(params,indent=2),encoding='utf-8')
issues=[]
def require(condition,code,details):
    if not condition:issues.append({'code':code,'details':details})
def load(path):
    raw=json.loads(path.read_text(encoding='utf-8'))
    return [{'p':f['properties'],'g':transform(fw,shape(f['geometry'])) if f.get('geometry') else None,'raw':f} for f in raw['features']]
def ktype(f):return f['p'].get('restriction_type', f['p']['object_type'])
def flat(g):
    if g.is_empty:return []
    return [p for x in g.geoms for p in flat(x)] if hasattr(g,'geoms') else [g]
def angle(a,b,c):
    v=(b[0]-a[0],b[1]-a[1]);w=(c[0]-b[0],c[1]-b[1])
    n=math.hypot(*v)*math.hypot(*w)
    return math.degrees(math.acos(max(-1,min(1,(v[0]*w[0]+v[1]*w[1])/n)))) if n>1e-10 else 0
def min_dn(flow):return next(d for d in sorted(params) if params[d]['capacity']+1e-8>=flow)
def price_chamber(dn):return next(p for lo,hi,p in chamber_bands if lo<=dn<=hi)

def audit(name,inp_path,out_path):
    inp=load(inp_path);out=load(out_path)
    targets={f['p']['id']:f for f in inp if ktype(f)=='oks_connection_point'}
    pipes=[f for f in inp if ktype(f)=='heat_network']
    old_chambers={f['p']['id']:f for f in inp if ktype(f)=='heat_chamber'}
    restrictions=[f for f in inp if f['p']['object_type']=='restriction']
    buildings=[f for f in restrictions if ktype(f)=='oks']
    own={k:[f for f in buildings if f['g'].covers(t['g'])] for k,t in targets.items()}
    report={}
    for vid in sorted({f['p']['variant_id'] for f in out}):
        begin=len(issues)
        selected=[f for f in out if f['p']['variant_id']==vid]
        edges=[f for f in selected if ktype(f)=='heat_network']
        new_nodes={f['p']['id']:f for f in selected if ktype(f) in {'heat_chamber','technical_node'}}
        nodes={**targets,**old_chambers,**new_nodes}
        declared=next(f['p'] for f in selected if ktype(f)=='variant_summary')
        adjacency=defaultdict(list)
        all_ids=[f['p']['id'] for f in selected]
        require(len(set(all_ids))==len(all_ids),'duplicate_id',[name,vid])
        for i,e in enumerate(edges):
            p=e['p'];a=p['start_node_id'];b=p['end_node_id'];g=e['g'];c=list(g.coords)
            e['ends']=(a,b)
            require(a in nodes and b in nodes,'unknown_node',[name,vid,p['id']])
            require(Point(c[0]).distance(nodes[a]['g'])<1e-4 and Point(c[-1]).distance(nodes[b]['g'])<1e-4,'endpoint_position',[name,vid,p['id']])
            require(g.is_simple and g.is_valid and g.length>0,'line_simplicity',[name,vid,p['id']])
            require(abs(g.length-p['length'])<1e-4,'line_length',[name,vid,p['id'],g.length,p['length']])
            require(p['depth_start'] is None and p['depth_end'] is None,'2d_depth',[name,vid,p['id']])
            for k in range(1,len(c)-1):require(angle(c[k-1],c[k],c[k+1])<=90+1e-5,'line_turn',[name,vid,p['id'],k,angle(c[k-1],c[k],c[k+1])])
            adjacency[a].append((b,i));adjacency[b].append((a,i))
        root_hosts={k:[p for p in pipes if p['g'].distance(f['g'])<1e-3] for k,f in {**old_chambers,**new_nodes}.items() if k in adjacency}
        roots={k for k,hosts in root_hosts.items() if hosts}
        components=[];seen=set()
        for node in adjacency:
            if node in seen:continue
            q=[node];comp=set();edge_ids=set()
            while q:
                n=q.pop()
                if n in comp:continue
                comp.add(n);seen.add(n)
                for other,ei in adjacency[n]:edge_ids.add(ei);q.append(other)
            r=roots & comp
            require(len(r)==1,'one_existing_tie_per_component',[name,vid,len(r)])
            require(len(edge_ids)==len(comp)-1,'acyclic_component',[name,vid,len(comp),len(edge_ids)])
            components.append({'root':next(iter(r)) if r else None,'nodes':len(comp),'edges':len(edge_ids),'targets':sorted(comp & targets.keys())})
        visited={r:None for r in roots};parent_edge={};order=[];q=deque(roots)
        while q:
            n=q.popleft();order.append(n)
            for other,ei in adjacency[n]:
                if other not in visited:visited[other]=n;parent_edge[other]=ei;q.append(other)
        require(len(visited)==len(adjacency),'root_reachability',[name,vid])
        downstream={k:({k} if k in targets else set()) for k in visited}
        for n in reversed(order):
            if visited[n] is not None:
                parent=visited[n];ei=parent_edge[n];e=edges[ei]
                flow=sum(targets[k]['p']['flow_tph'] for k in downstream[n])
                require(abs(flow-e['p']['flow_tph'])<1e-7,'flow_sum',[name,vid,e['p']['id'],flow,e['p']['flow_tph']])
                require(params[e['p']['diameter']]['capacity']+1e-7>=flow,'capacity',[name,vid,e['p']['id']])
                e['downstream_targets']=sorted(downstream[n]);e['derived_flow']=flow
                downstream[parent].update(downstream[n])
        root_slots={}
        chamber_total=0; chamber_rows=[]
        for k,f in new_nodes.items():
            inc=[edges[ei] for _,ei in adjacency[k]]
            hosts=root_hosts.get(k,[])
            existing_slots=sum(1 if f['g'].distance(Point(p['g'].coords[0]))<1e-3 or f['g'].distance(Point(p['g'].coords[-1]))<1e-3 else 2 for p in hosts)
            root_slots[k]=len(inc)+existing_slots
            require(root_slots[k]<=4,'chamber_degree',[name,vid,k,root_slots[k]])
            if ktype(f)=='technical_node':
                require(len(inc)==2,'technical_node_degree',[name,vid,k]);continue
            dn=max([e['p']['diameter'] for e in inc]+[p['p']['diameter'] for p in hosts])
            cost=price_chamber(dn);chamber_total+=cost
            require(f['p']['diameter']==dn and abs(f['p']['cost']-cost)<0.01,'chamber_price',[name,vid,k,dn,cost])
            close=[c for c in old_chambers.values() if c['g'].distance(f['g'])<=10+1e-6]
            if hosts:
                require(not close,'old_chamber_10m',[name,vid,k,[c['p']['id'] for c in close]])
            chamber_rows.append({'id':k,'dn_including_existing':dn,'cost':cost,'total_incidence':root_slots[k]})
        max_runs={}; target_rows=[]
        for k in sorted(targets):
            if k not in visited:
                target_rows.append({'id':k,'connected':False,'flow':targets[k]['p']['flow_tph']});continue
            require(len(adjacency[k])==1,'target_leaf',[name,vid,k])
            node=k;path=[];run_dn=None;run_len=0;previous_dn=0;downward=None
            while visited[node] is not None:
                e=edges[parent_edge[node]];p=e['p'];dn=p['diameter']
                require(dn>=previous_dn,'dn_monotonic',[name,vid,k,p['id']])
                if dn!=run_dn:
                    if run_dn is not None:require(run_len<=params[run_dn]['max_length']+1e-4,'dn_run_length',[name,vid,k,run_dn,run_len])
                    run_dn=dn;run_len=0
                run_len+=e['g'].length;max_runs[dn]=max(max_runs.get(dn,0),run_len)
                require(run_len<=params[dn]['max_length']+1e-4,'dn_run_length',[name,vid,k,dn,run_len])
                if downward is not None:
                    at=nodes[node]['g'];c1=list(e['g'].coords);c2=list(downward['g'].coords)
                    a=c1[1] if Point(c1[0]).distance(at)<1e-4 else c1[-2]
                    b=c2[1] if Point(c2[0]).distance(at)<1e-4 else c2[-2]
                    require(angle(a,at.coords[0],b)<=90+1e-5,'node_turn',[name,vid,node])
                path.append(p['id']);previous_dn=dn;downward=e;node=visited[node]
            target_rows.append({'id':k,'connected':True,'flow':targets[k]['p']['flow_tph'],'root':node,'path_edges':path})
        crossings=[]
        for i,e in enumerate(edges):
            for other in edges[i+1:]:
                cross=e['g'].intersection(other['g'])
                if cross.is_empty:continue
                common=set(e['ends']) & set(other['ends'])
                valid=all(part.geom_type=='Point' and any(nodes[n]['g'].distance(part)<1e-4 for n in common) for part in flat(cross))
                require(valid,'new_network_crossing',[name,vid,e['p']['id'],other['p']['id'],cross.wkt])
                crossings.append([e['p']['id'],other['p']['id']])
        terminal_rows=[]; gap_rows=[]
        for e in edges:
            p=e['p'];g=e['g'];dn=p['diameter'];half=params[dn]['width']/2
            for f in restrictions:
                typ=ktype(f)
                if typ not in {'oks','water','railway','park','social_area','prohibited_site'}:continue
                exempt_targets=[k for k in e['ends'] if k in targets and f in own[k]]
                test=g
                if exempt_targets:
                    require(len(exempt_targets)==1,'one_terminal_per_edge',[name,vid,p['id']])
                    target_id=exempt_targets[0];pt=targets[target_id]['g'];coords=list(g.coords)
                    if Point(coords[-1]).distance(pt)>1e-4:coords.reverse()
                    end=coords[-1];start=coords[-2];dx=end[0]-start[0];dy=end[1]-start[1];norm=math.hypot(dx,dy)
                    last=len(coords)-2
                    while last>0 and abs((coords[last-1][0]-end[0])*dy-(coords[last-1][1]-end[1])*dx)/norm<1e-5:last-=1
                    final=LineString(coords[last:]);test=LineString(coords[:last+1]) if last>0 else None
                    pieces=list(f['g'].geoms) if hasattr(f['g'],'geoms') else [f['g']]
                    own_part=next(x for x in pieces if x.covers(pt))
                    outer=LineString(own_part.exterior.coords)
                    border=final.intersection(outer)
                    require(border.geom_type=='Point','single_exterior_entry',[name,vid,p['id'],border.wkt])
                    inside=final.intersection(own_part).length
                    require(abs(inside-border.distance(pt))<1e-4,'straight_inside_own_oks',[name,vid,p['id']])
                    strict_point=nearest_points(pt,outer)[1]
                    v=((strict_point.x-pt.x)/pt.distance(strict_point),(strict_point.y-pt.y)/pt.distance(strict_point))
                    strict_ray=LineString([pt.coords[0],(strict_point.x+v[0]*(5+half+0.2),strict_point.y+v[1]*(5+half+0.2))])
                    blockers=[]
                    for other in restrictions:
                        if other is f or ktype(other) not in {'oks','water','railway','park','social_area','prohibited_site'}:continue
                        need=(5 if ktype(other)=='oks' else 1)+half
                        if strict_ray.distance(other['g'])<need-1e-4:blockers.append(other['p']['id'])
                    terminal_rows.append({'id':target_id,'building':f['p']['id'],'inside_length_m':inside,'nearest_exterior_m':pt.distance(outer),'nearest_any_boundary_m':pt.distance(own_part.boundary),'extra_inside_m':inside-pt.distance(outer),'strict_exit_other_blockers':blockers,'output_edge':p['id']})
                if test is None:continue
                required=(5 if dn<500 else 7 if dn<=800 else 9) if typ=='oks' else 1
                measured=test.distance(f['g'])-half
                margin=measured-required
                gap_rows.append({'edge':p['id'],'restriction':f['p']['id'],'type':typ,'outside_gap_m':measured,'required_m':required,'margin_m':margin})
                require(margin>=-1e-4,'restriction_gap',[name,vid,p['id'],f['p']['id'],margin])
        # Price every edge from independent geometry and original price table.
        # For special edges derive K from actual intersected objects on the straight
        # extended axis, using original Table 2 margins; no stored cost is trusted.
        cost_rows=[];pipe_total=0; special_rules={'road':(3,1.6),'tram_tracks':(3,1.75),'gas_pipeline':(2,1.25),'power_cable':(2,1.15),'heat_network':(2,1.05)}
        for e in edges:
            p=e['p'];g=e['g'];ks=1;objects=[]
            if p['laying_method']=='special':
                coords=list(g.coords);a=coords[0];b=coords[-1];l=Point(a).distance(Point(b))
                require(abs(l-g.length)<1e-4,'special_straight',[name,vid,p['id']])
                u=((b[0]-a[0])/l,(b[1]-a[1])/l)
                axis=LineString([(a[0]-u[0]*100,a[1]-u[1]*100),(b[0]+u[0]*100,b[1]+u[1]*100)])
                midpoint=g.interpolate(0.5,normalized=True);station=axis.project(midpoint)
                for f in restrictions+pipes:
                    typ=ktype(f)
                    if typ not in special_rules:continue
                    margin,k=special_rules[typ]
                    for part in flat(axis.intersection(f['g'])):
                        positions=[axis.project(Point(c)) for c in part.coords]
                        if min(positions)-margin-1e-4<=station<=max(positions)+margin+1e-4:
                            ks=max(ks,k);objects.append({'id':f['p']['id'],'type':typ,'k':k})
                require(ks>1,'special_cost_object',[name,vid,p['id']])
            else:require(p['laying_method']=='base','laying_method',[name,vid,p['id']])
            cost=g.length*params[p['diameter']]['price']*ks
            pipe_total+=cost
            require(abs(cost-p['cost'])<0.01,'pipe_cost',[name,vid,p['id'],cost,p['cost'],ks])
            cost_rows.append({'id':p['id'],'length_m':g.length,'dn':p['diameter'],'flow_from_graph':e.get('derived_flow'),'flow_minimum_dn':min_dn(e['derived_flow']),'price_per_m':params[p['diameter']]['price'],'k_special':ks,'cost':cost,'special_objects':objects})
        connected=set(targets)&set(visited)
        unconnected=sorted(set(targets)-connected)
        penalty=sum(100_000_000+500_000*targets[k]['p']['flow_tph'] for k in unconnected)
        tie_count=sum(len(adjacency[k]) for k in old_chambers if k in adjacency)
        tie_cost=tie_count*5_000_000
        construction=pipe_total+chamber_total+tie_cost
        length=sum(e['g'].length for e in edges)
        total=construction+penalty
        score_c=0.7*total/25_000_000;score_l=0.3*length/100;score=score_c+score_l
        for field,expected in [('construction_cost',construction),('chamber_construction_cost',chamber_total),('existing_chamber_tie_in_count',tie_count),('existing_chamber_tie_in_cost',tie_cost),('unconnected_penalty',penalty),('calculated_cost',total),('new_network_length',length),('score',score)]:
            require(abs(expected-declared[field])<(1e-6 if field=='score' else 0.01),'summary_'+field,[name,vid,expected,declared[field]])
        require(unconnected==declared['unconnected_oks_ids'],'unconnected_list',[name,vid,unconnected,declared['unconnected_oks_ids']])
        geodesic=sum(geod.geometry_length(shape(e['raw']['geometry'])) for e in edges)
        report[vid]={'target_count':len(targets),'connected_count':len(connected),'total_flow_tph':sum(t['p']['flow_tph'] for t in targets.values()),'length_m_from_coordinates':length,'geodesic_length_check_m':geodesic,'pipe_cost':pipe_total,'chamber_cost':chamber_total,'tie_in_cost':tie_cost,'penalty':penalty,'construction_cost':construction,'total_cost':total,'score_cost_term':score_c,'score_length_term':score_l,'score':score,'declared_score':declared['score'],'new_lines':len(edges),'new_chambers':len(chamber_rows),'flow_dn_upgrades_above_capacity_minimum':[r for r in cost_rows if r['dn']!=r['flow_minimum_dn']],'same_dn_longest_runs_m':max_runs,'components':components,'targets':target_rows,'terminals':sorted(terminal_rows,key=lambda x:x['id']),'tightest_hard_gaps':sorted(gap_rows,key=lambda x:x['margin_m'])[:12],'pipe_items':cost_rows,'chamber_items':chamber_rows,'issues':issues[begin:]}
        print(name,vid,'connected',len(connected),'S',score,'pipe',pipe_total,'chambers',chamber_total,'new_issues',len(issues)-begin,flush=True)
    return report

inp=repo/'task/sources/Датасет скорректированный.geojson'
reports={}
for name,source,out in [('main',inp,repo/'results/final_main.geojson'),('strict',inp,repo/'results/final_strict.geojson'),('osm',repo/'scenarios/osm_roads/enriched.geojson',repo/'results/scenario_osm_roads.geojson')]:
    reports[name]=audit(name,source,out)
main=reports['main']['v1'];strict=reports['strict']['v1']
decomposition={'removed_penalty_rub':strict['penalty']-main['penalty'],'score_reduction_from_removed_penalty':0.7*(strict['penalty']-main['penalty'])/25_000_000,'extra_construction_rub':main['construction_cost']-strict['construction_cost'],'score_increase_from_extra_construction':0.7*(main['construction_cost']-strict['construction_cost'])/25_000_000,'extra_length_m':main['length_m_from_coordinates']-strict['length_m_from_coordinates'],'score_increase_from_extra_length':0.3*(main['length_m_from_coordinates']-strict['length_m_from_coordinates'])/100,'net_score_change':main['score']-strict['score']}
result={'method':'Original DOCX table extraction; independent Python graph traversal, Shapely and pyproj. No project modules imported. Hard-gap tolerance 0.1 mm; endpoint tolerance 0.1 mm. Special K derived from geometry.','source_docx_sha256':hashlib.sha256(docpath.read_bytes()).hexdigest(),'reports':reports,'strict_to_main_decomposition':decomposition,'issues':issues}
(root/'independent-score.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print('issues',len(issues),flush=True)
for i in issues[:30]:print(i,flush=True)
