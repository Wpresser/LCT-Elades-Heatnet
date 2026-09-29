export type Feature = { type: 'Feature'; properties: Record<string, any>; geometry: any }
export type Collection = { type: 'FeatureCollection'; features: Feature[]; [key: string]: any }

export type ProofRecord = {
  target_id: string | number
  containing_oks_id?: string | number
  geometry_type?: string
  component_count?: number
  is_valid?: boolean
  make_valid_type?: string
  nearest_distance_m?: number
  make_valid_nearest_distance_m?: number
  nearest_boundary_xy?: number[]
  first_reentry_m?: number
  first_clearance_exit_m?: number
  own_intervals_m?: number[][]
  setback_intervals_m?: number[][]
  blocked?: boolean
  visual_geojson?: string
}

export type Diagnostic = { proof: ProofRecord; visual: Collection }
export type SegmentStats = { consumers: Array<string | number>; aggregatedFlow: number; shared: boolean }
export type ChamberStats = { incidentCount: number; maxDiameter?: number }

export type SceneData = {
  optimized: Collection
  baseline: Collection
  input: Collection
  summary: Record<string, any>
  baselineSummary: Record<string, any>
  inputSummary: {
    targets: number
    existingNetwork: number
    existingChambers: number
    restrictions: number
    restrictionTypes: Array<{ type: string; count: number }>
  }
  newChamberIds: Set<string | number>
  existingChamberIds: Set<string | number>
  newChamberKinds: Map<string, 'tie-in'|'branch'>
  newChamberAliases: Map<string, string>
  sharedIds: Set<string | number>
  sharedRawIds: Array<string | number>
  segmentStats: Map<string, SegmentStats>
  chamberStats: Map<string, ChamberStats>
  diagnostics: Map<string, Diagnostic>
  sourceName?: string
  engineeringLabels: Collection
}

const idKey = (value: unknown) => `${typeof value}:${String(value)}`
const collection = (features: Feature[]): Collection => ({ type: 'FeatureCollection', features })

function lineLength(feature: Feature) {
  const c = feature.geometry?.coordinates
  if (!Array.isArray(c) || feature.geometry.type === 'Point') return 0
  const lines = feature.geometry.type === 'MultiLineString' ? c : [c]
  return lines.reduce((total: number, line: number[][]) => total + line.reduce((n, p, i) => i ? n + Math.hypot(p[0]-line[i-1][0], p[1]-line[i-1][1]) : n, 0), 0)
}

function segmentDistance(p: number[], a: number[], b: number[]) {
  if (!a || !b) return Number.POSITIVE_INFINITY
  const dx=b[0]-a[0], dy=b[1]-a[1], den=dx*dx+dy*dy
  const t=den ? Math.max(0,Math.min(1,((p[0]-a[0])*dx+(p[1]-a[1])*dy)/den)) : 0
  return Math.hypot(p[0]-(a[0]+t*dx),p[1]-(a[1]+t*dy))
}

function pointOf(feature: Feature) {
  return feature.geometry?.type === 'Point' ? feature.geometry.coordinates : undefined
}

function buildDownstreamStats(network: Feature[], targets: Feature[], sharedIds: Set<string | number>) {
  const targetIds = new Set(targets.map(f => idKey(f.properties?.id)))
  const flowByTarget = new Map(targets.map(f => [idKey(f.properties?.id), Number(f.properties?.flow_tph) || 0]))
  const outgoing = new Map<string, Feature[]>()
  network.forEach(edge => {
    const key = idKey(edge.properties?.start_node_id)
    const edges = outgoing.get(key) ?? []
    edges.push(edge)
    outgoing.set(key, edges)
  })
  const collect = (node: unknown, seen: Set<string>): Set<string> => {
    const key = idKey(node)
    if (seen.has(key)) return new Set()
    const nextSeen = new Set(seen).add(key)
    const result = new Set<string>()
    if (targetIds.has(key)) result.add(key)
    for (const edge of outgoing.get(key) ?? []) collect(edge.properties?.end_node_id, nextSeen).forEach(id => result.add(id))
    return result
  }
  const stats = new Map<string, SegmentStats>()
  network.forEach(edge => {
    const consumers = Array.from(collect(edge.properties?.end_node_id, new Set())).map(key => {
      const feature = targets.find(target => idKey(target.properties?.id) === key)
      return feature?.properties?.id
    }).filter((id): id is string | number => typeof id === 'string' || typeof id === 'number')
    const key = idKey(edge.properties?.id)
    const shared = consumers.length > 1 || sharedIds.has(key)
    if (shared) sharedIds.add(key)
    stats.set(key, { consumers, aggregatedFlow: Number(edge.properties?.flow_tph) || consumers.reduce((sum: number, id) => sum + Number(flowByTarget.get(idKey(id)) ?? 0), 0), shared })
  })
  return stats
}

export function adaptScene(
  optimized: Collection,
  baseline: Collection,
  input: Collection,
  proofRecords: ProofRecord[] = [],
  diagnosticCollections: Record<string, Collection | null> = {},
): SceneData {
  const summary = optimized.features.find(f => f.properties?.object_type === 'variant_summary')?.properties ?? {}
  const baselineSummary = baseline.features.find(f => f.properties?.object_type === 'variant_summary')?.properties ?? {}
  const inputChambers = input.features.filter(f => f.properties?.object_type === 'heat_chamber')
  const inputTargets = input.features.filter(f => f.properties?.object_type === 'oks_connection_point')
  const inputLines = input.features.filter(f => f.properties?.object_type === 'heat_network')
  const outputNetwork = optimized.features.filter(f => f.properties?.object_type === 'heat_network')
  const outputChambers = optimized.features.filter(f => f.properties?.object_type === 'heat_chamber')
  const existingChamberIds = new Set(inputChambers.map(f => idKey(f.properties.id)))
  const newChamberIds = new Set(outputChambers.map(f => idKey(f.properties.id)))
  const distanceToInput = (point: number[]) => inputLines.reduce((best, line) => {
    const coords = line.geometry?.coordinates ?? []
    const lines = line.geometry?.type === 'MultiLineString' ? coords : [coords]
    return Math.min(best, ...lines.flatMap((ls: number[][]) => ls.slice(1).map((p, i) => segmentDistance(point, ls[i], p))))
  }, Number.POSITIVE_INFINITY)
  const newChamberKinds = new Map<string, 'tie-in'|'branch'>()
  outputChambers.forEach(f => {
    const point = pointOf(f)
    const tieIn = typeof f.properties.diag_tie_in === 'boolean'
      ? f.properties.diag_tie_in
      : Boolean(point && distanceToInput(point) < 0.00003)
    newChamberKinds.set(idKey(f.properties.id), tieIn ? 'tie-in' : 'branch')
  })
  const newChamberAliases = new Map<string, string>()
  outputChambers.slice().sort((a,b) => String(a.properties.id).localeCompare(String(b.properties.id))).forEach((feature, index) => newChamberAliases.set(idKey(feature.properties.id), `НК-${index + 1}`))

  const outgoing = new Map<string, number>()
  outputNetwork.forEach(f => {
    const p = f.properties
    outgoing.set(idKey(p.start_node_id), (outgoing.get(idKey(p.start_node_id)) ?? 0) + 1)
  })
  const targetIds = new Set(inputTargets.map(f => idKey(f.properties.id)))
  const sharedIds = new Set<string | number>()
  outputNetwork.forEach(f => {
    const p = f.properties
    if ((outgoing.get(idKey(p.end_node_id)) ?? 0) > 1 || (!targetIds.has(idKey(p.end_node_id)) && Number(p.flow_tph) > Number(summary.max_single_consumer_flow ?? 0))) sharedIds.add(idKey(p.id))
  })
  const branchNodes = new Set(outputChambers.map(f => idKey(f.properties.id)))
  outputNetwork.forEach(f => { if (branchNodes.has(idKey(f.properties.end_node_id))) sharedIds.add(idKey(f.properties.id)) })
  const segmentStats = buildDownstreamStats(outputNetwork, inputTargets, sharedIds)
  const sharedRawIds = outputNetwork.filter(f => sharedIds.has(idKey(f.properties?.id))).map(f => f.properties?.id).filter((id): id is string | number => typeof id === 'string' || typeof id === 'number')

  const chamberStats = new Map<string, ChamberStats>()
  outputChambers.forEach(chamber => {
    const key = idKey(chamber.properties?.id)
    const incident = outputNetwork.filter(edge => idKey(edge.properties?.start_node_id) === key || idKey(edge.properties?.end_node_id) === key)
    const diameters = incident.map(edge => Number(edge.properties?.diameter)).filter(Number.isFinite)
    chamberStats.set(key, { incidentCount: incident.length, maxDiameter: diameters.length ? Math.max(...diameters) : undefined })
  })

  const restrictionCounts = new Map<string, number>()
  input.features.filter(f => f.properties?.object_type === 'restriction').forEach(f => {
    const type = String(f.properties?.restriction_type ?? 'не указан')
    restrictionCounts.set(type, (restrictionCounts.get(type) ?? 0) + 1)
  })
  const diagnostics = new Map<string, Diagnostic>()
  proofRecords.forEach(proof => {
    const visual = proof.visual_geojson ? diagnosticCollections[proof.visual_geojson] : null
    if (visual) diagnostics.set(idKey(proof.target_id), { proof, visual })
  })

  const labelFeatures: Feature[] = []
  input.features.filter(f => f.properties?.object_type === 'source' && pointOf(f)).forEach(f => labelFeatures.push({ ...f, properties: { ...f.properties, label: f.properties?.name ?? `Источник ${f.properties?.id}`, labelKind: 'source' } }))
  inputChambers.filter(f => pointOf(f)).forEach(f => labelFeatures.push({ ...f, properties: { ...f.properties, label: `Камера ${f.properties?.id}`, labelKind: 'existing_chamber' } }))
  outputChambers.filter(f => pointOf(f)).forEach(f => labelFeatures.push({ ...f, properties: { ...f.properties, label: newChamberAliases.get(idKey(f.properties?.id)), labelKind: 'new_chamber' } }))
  inputTargets.filter(f => pointOf(f)).forEach(f => labelFeatures.push({ ...f, properties: { ...f.properties, label: `ОКС ${f.properties?.id}`, labelKind: 'consumer' } }))
  outputNetwork.forEach(f => {
    const diameter = f.properties?.diameter
    const flow = f.properties?.flow_tph
    const label = diameter && flow ? `ДУ ${diameter} · ${Number(flow).toFixed(1)} т/ч` : diameter ? `ДУ ${diameter}` : flow ? `${Number(flow).toFixed(1)} т/ч` : undefined
    if (label) labelFeatures.push({ ...f, properties: { ...f.properties, label, labelKind: 'network' } })
  })

  const inputSummary = {
    targets: inputTargets.length,
    existingNetwork: inputLines.length,
    existingChambers: inputChambers.length,
    restrictions: input.features.filter(f => f.properties?.object_type === 'restriction').length,
    restrictionTypes: Array.from(restrictionCounts.entries()).sort((a,b) => a[0].localeCompare(b[0])).map(([type, count]) => ({ type, count })),
  }
  return {
    optimized, baseline, input, summary, baselineSummary, newChamberKinds, newChamberAliases, inputSummary,
    newChamberIds, existingChamberIds, sharedIds, sharedRawIds, segmentStats, chamberStats, diagnostics,
    sourceName: input.features.find(f => f.properties?.object_type === 'source')?.properties?.name,
    engineeringLabels: collection(labelFeatures),
  }
}

export const featureId = (f: Feature) => idKey(f.properties?.id)
export function unconnectedInfo(summary: Record<string, any>, id: unknown): { policy: string; reason: string } | null {
  const ids: unknown[] = Array.isArray(summary.unconnected_oks_ids) ? summary.unconnected_oks_ids : []
  if (!ids.some(value => idKey(value) === idKey(id))) return null
  const reason = summary.diag_unconnected_reasons?.[String(id)]
  return {
    policy: String(summary.diag_terminal_policy ?? 'literal'),
    reason: typeof reason === 'string' && reason ? reason : 'Допустимый маршрут не найден при соблюдении ограничений.',
  }
}
export const asGeoJSON = (features: Feature[]): Collection => collection(features)
export { lineLength }
