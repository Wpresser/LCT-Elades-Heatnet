import React, { useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import * as maplibregl from 'maplibre-gl'
import { Map, MapMouseEvent } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import 'maplibre-gl/dist/maplibre-gl.css'
import './styles.css'
import { adaptScene, asGeoJSON, Collection, Diagnostic, Feature, featureId, ProofRecord, SceneData, unconnectedInfo } from './data'
import { policyCopy, Policy, variantCollection } from './policy'

maplibregl.setWorkerUrl(workerUrl)

type LayerKey = 'existingNetwork' | 'newNetwork' | 'existingChambers' | 'newChambers' | 'consumers' | 'oks' | 'restrictions' | 'source' | 'flow' | 'shared' | 'labels'
type Layers = Record<LayerKey, boolean>
const initialLayers: Layers = {
  existingNetwork: true, newNetwork: true, existingChambers: true, newChambers: true,
  consumers: true, oks: true, restrictions: true, source: true, flow: false, shared: false, labels: false,
}
const label: Record<LayerKey, string> = {
  existingNetwork: 'Существующая сеть', newNetwork: 'Новая сеть', existingChambers: 'Существующие камеры',
  newChambers: 'Новые камеры', consumers: 'Потребители', oks: 'Здания ОКС', restrictions: 'Ограничения',
  source: 'Источник', flow: 'Показать поток', shared: 'Общие магистрали', labels: 'Инженерные подписи',
}
const fmtMoney = (n: number) => Number.isFinite(n) ? `${(n / 1e6).toFixed(1)}M ₽` : '—'
const fmtNum = (n: number, digits = 3) => Number.isFinite(n) ? n.toFixed(digits) : '—'
const idKey = (v: unknown) => `${typeof v}:${String(v)}`
const humanObject = (value: unknown) => ({
  heat_network: 'Участок новой сети', heat_chamber: 'Тепловая камера', oks_connection_point: 'Точка подключения',
  source: 'Источник теплоснабжения', restriction: 'Пространственное ограничение',
} as Record<string, string>)[String(value)] ?? String(value ?? 'Объект')
const humanChamber = (value: unknown) => value === 'tie-in' ? 'Новая камера присоединения' : value === 'branch' ? 'Новая камера разветвления' : 'Камера'
const humanStatus = (value: unknown) => value === 'connected' ? 'Подключён' : value === 'unconnected' ? 'Не подключён' : 'Статус не определён'

async function fetchJson<T>(url: string, optional = false): Promise<T | null> {
  try {
    const response = await fetch(url)
    if (!response.ok) throw new Error(`${url} ${response.status}`)
    return await response.json() as T
  } catch (error) {
    if (optional) return null
    throw error
  }
}

const assetPath = (path: string) => `${import.meta.env.BASE_URL}${path.replace(/^\//, '')}`

function sourceData(data: SceneData) {
  const output = variantCollection(data.optimized, 'v1')
  const existing = data.input.features.filter(f => ['heat_network', 'heat_chamber', 'source', 'oks_connection_point', 'restriction'].includes(f.properties?.object_type))
  return { output, existing }
}

function mappedInput(data: SceneData) {
  const { output, existing } = sourceData(data)
  const summary = data.summary
  const unconnected = new Set((Array.isArray(summary.unconnected_oks_ids) ? summary.unconnected_oks_ids : []).map(idKey))
  const connected = new Set(output.features.filter(f => f.properties?.object_type === 'heat_network').map(f => idKey(f.properties?.end_node_id)))
  return existing.map(f => f.properties?.object_type === 'oks_connection_point'
    ? { ...f, properties: { ...f.properties, status: unconnected.has(idKey(f.properties?.id)) ? 'unconnected' : connected.has(idKey(f.properties?.id)) ? 'connected' : 'unknown' } }
    : f)
}

function collectCoordinates(value: any, result: [number, number][]) {
  if (!Array.isArray(value)) return
  if (value.length >= 2 && typeof value[0] === 'number' && typeof value[1] === 'number') {
    result.push([value[0], value[1]])
    return
  }
  value.forEach(item => collectCoordinates(item, result))
}

function boundsForFeatures(features: Feature[]) {
  const coordinates: [number, number][] = []
  features.forEach(feature => collectCoordinates(feature.geometry?.coordinates, coordinates))
  if (!coordinates.length) return null
  return coordinates.reduce((bounds, coordinate) => bounds.extend(coordinate), new maplibregl.LngLatBounds(coordinates[0], coordinates[0]))
}

function focusFeatures(map: Map, features: Feature[], duration = 950) {
  const bounds = boundsForFeatures(features)
  if (!bounds) return
  const sw = bounds.getSouthWest()
  const ne = bounds.getNorthEast()
  const span = Math.max(Math.abs(ne.lng - sw.lng), Math.abs(ne.lat - sw.lat))
  if (span < 0.00035) {
    map.flyTo({ center: bounds.getCenter(), zoom: 17, pitch: 52, bearing: -15, duration })
  } else {
    map.fitBounds(bounds, { padding: 170, duration, pitch: 48, bearing: -15 })
  }
}

function App() {
  const mapRef = useRef<Map | null>(null)
  const nodeRef = useRef<HTMLDivElement | null>(null)
  const [data, setData] = useState<SceneData | null>(null)
  const [policy, setPolicy] = useState<Policy>('strict')
  const [policyOutputs, setPolicyOutputs] = useState<{ strict: Collection; alternative: Collection } | null>(null)
  const [inputCollection, setInputCollection] = useState<Collection | null>(null)
  const [proofRecords, setProofRecords] = useState<ProofRecord[]>([])
  const [visualCollections, setVisualCollections] = useState<Record<string, Collection | null>>({})
  const [layers, setLayers] = useState<Layers>(initialLayers)
  const [selected, setSelected] = useState<Feature | null>(null)
  const [hovered, setHovered] = useState<Feature | null>(null)
  const [hoverPoint, setHoverPoint] = useState<{ x: number; y: number } | null>(null)
  const [presentation, setPresentation] = useState(false)
  const [scoreOpen, setScoreOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([
      fetchJson<Collection>(assetPath('/data/strict.geojson')),
      fetchJson<Collection>(assetPath('/data/alternative.geojson')),
      fetchJson<Collection>(assetPath('/data/input.geojson')),
    ]).then(async ([strict, alternative, input]) => {
      if (!strict || !alternative || !input) throw new Error('Не удалось загрузить основные данные')
      const manifest = await fetchJson<{ optional: string[] }>(assetPath('/data/manifest.json'))
      const available = new Set(manifest?.optional ?? [])
      const rawProof = available.has('proof.json') ? await fetchJson<ProofRecord[]>(assetPath('/data/proof.json'), true) : []
      const proof = Array.isArray(rawProof) ? rawProof.filter(record => record && ['string', 'number'].includes(typeof record.target_id)) : []
      const visualEntries = await Promise.all(proof.map(async record => {
        if (!record.visual_geojson || !available.has(record.visual_geojson)) return [record.visual_geojson ?? '', null] as const
        return [record.visual_geojson, await fetchJson<Collection>(assetPath(`/data/${record.visual_geojson}`), true)] as const
      }))
      const visuals: Record<string, Collection | null> = Object.fromEntries(visualEntries)
      setPolicyOutputs({ strict, alternative })
      setInputCollection(input)
      setProofRecords(proof)
      setVisualCollections(visuals)
      setData(adaptScene(variantCollection(strict, 'v1'), variantCollection(strict, 'v2'), input, proof, visuals))
    }).catch(e => setError(e instanceof Error ? e.message : String(e))).finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!policyOutputs || !inputCollection) return
    const output = policyOutputs[policy]
    setData(adaptScene(variantCollection(output, 'v1'), variantCollection(output, 'v2'), inputCollection, proofRecords, visualCollections))
    setSelected(null)
  }, [policy, policyOutputs, inputCollection, proofRecords, visualCollections])

  const metrics = useMemo(() => {
    const summary = data?.summary ?? {}
    const missing = Array.isArray(summary.unconnected_oks_ids) ? summary.unconnected_oks_ids.length : 0
    const branchChambers = data ? Array.from(data.newChamberKinds.values()).filter(kind => kind === 'branch').length : 0
    return {
      coverage: `${(data?.inputSummary.targets ?? 0) - missing} / ${data?.inputSummary.targets ?? 0}`,
      score: Number(summary.score), cost: Number(summary.calculated_cost), length: Number(summary.new_network_length),
      chambers: branchChambers, missing,
    }
  }, [data])
  const baselineScore = Number(data?.baselineSummary?.score)
  const baselineLength = Number(data?.baselineSummary?.new_network_length)
  const scoreImprovement = Number.isFinite(baselineScore) && baselineScore ? (1 - metrics.score / baselineScore) * 100 : NaN
  const lengthReduction = Number.isFinite(baselineLength) && Number.isFinite(metrics.length) ? (baselineLength - metrics.length) / 1000 : NaN
  const fit = () => {
    const map = mapRef.current
    if (!map || !data) return
    focusFeatures(map, [...data.input.features, ...data.optimized.features], 850)
  }

  useEffect(() => {
    if (!nodeRef.current || !data || mapRef.current) return
    const map = new maplibregl.Map({
      container: nodeRef.current, center: [37.62, 55.75], zoom: 13, pitch: 48, bearing: -15,
      style: {
        version: 8,
        sources: {},
        layers: [{ id: 'bg', type: 'background', paint: { 'background-color': '#141413' } }],
      },
    })
    mapRef.current = map
    map.on('error', event => console.error('MapLibre:', event.error))
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'bottom-right')
    map.on('load', () => {
      const { output } = sourceData(data)
      const mappedExisting = mappedInput(data)
      map.addSource('output', { type: 'geojson', data: asGeoJSON(output.features) as any })
      map.addSource('input', { type: 'geojson', data: asGeoJSON(mappedExisting) as any })
      map.addSource('labels', { type: 'geojson', data: asGeoJSON(data.engineeringLabels.features) as any })
      map.addLayer({ id: 'oks-fill', type: 'fill-extrusion', source: 'input', filter: ['all', ['==', ['get', 'object_type'], 'restriction'], ['==', ['get', 'restriction_type'], 'oks']], paint: { 'fill-extrusion-color': '#faf9f5', 'fill-extrusion-opacity': 0.26, 'fill-extrusion-height': 8, 'fill-extrusion-base': 0 } })
      map.addLayer({ id: 'oks-outline', type: 'line', source: 'input', filter: ['all', ['==', ['get', 'object_type'], 'restriction'], ['==', ['get', 'restriction_type'], 'oks']], paint: { 'line-color': '#faf9f5', 'line-opacity': 0.48, 'line-width': 1.05 } })
      map.addLayer({ id: 'restriction-line', type: 'line', source: 'input', filter: ['==', ['get', 'object_type'], 'restriction'], paint: { 'line-color': '#8d786b', 'line-opacity': 0.42, 'line-width': 1.15, 'line-dasharray': [2, 3] } })
      map.addLayer({ id: 'existing-network', type: 'line', source: 'input', filter: ['==', ['get', 'object_type'], 'heat_network'], paint: { 'line-color': '#77756f', 'line-opacity': 0.66, 'line-width': 2.1 } })
      map.addLayer({ id: 'new-glow', type: 'line', source: 'output', filter: ['==', ['get', 'object_type'], 'heat_network'], paint: { 'line-color': '#9eb8ea', 'line-opacity': 0.13, 'line-blur': 5, 'line-width': ['interpolate', ['linear'], ['get', 'diameter'], 50, 5, 200, 10, 800, 17] } })
      map.addLayer({ id: 'new-network', type: 'line', source: 'output', filter: ['==', ['get', 'object_type'], 'heat_network'], paint: { 'line-color': ['match', ['get', 'diameter'], 65, '#8fd3d1', 100, '#8fb7e8', 125, '#b9a0e8', 150, '#e4be7c', 200, '#df8c78', '#9eb8ea'], 'line-opacity': 0.96, 'line-width': ['interpolate', ['linear'], ['get', 'diameter'], 50, 1.8, 200, 4.5, 800, 8], 'line-offset': 0 } })
      map.addLayer({ id: 'flow-network', type: 'line', source: 'output', filter: ['==', ['get', 'object_type'], 'heat_network'], layout: { visibility: 'none' }, paint: { 'line-color': ['interpolate', ['linear'], ['to-number', ['get', 'flow_tph']], 0, '#8fb7e8', 30, '#e4be7c', 60, '#d97757', 100, '#fff0d2'], 'line-opacity': 0.92, 'line-blur': 0.35, 'line-width': ['interpolate', ['linear'], ['to-number', ['get', 'flow_tph']], 0, 2.4, 30, 3.8, 60, 5.6, 100, 8] } })
      map.addLayer({ id: 'flow-arrows', type: 'symbol', source: 'output', filter: ['==', ['get', 'object_type'], 'heat_network'], layout: { visibility: 'none', 'symbol-placement': 'line', 'symbol-spacing': 86, 'text-field': '›', 'text-size': 17, 'text-font': ['Arial', 'sans-serif'], 'text-keep-upright': false, 'text-allow-overlap': true, 'text-ignore-placement': true }, paint: { 'text-color': '#fff0d2', 'text-halo-color': '#141413', 'text-halo-width': 1.3, 'text-opacity': 0.94 } })
      map.addLayer({ id: 'flow-labels', type: 'symbol', source: 'output', filter: ['==', ['get', 'object_type'], 'heat_network'], layout: { visibility: 'none', 'symbol-placement': 'line', 'symbol-spacing': 260, 'text-field': ['concat', 'ДУ ', ['to-string', ['get', 'diameter']], ' · ', ['to-string', ['get', 'flow_tph']], ' т/ч'], 'text-size': 10, 'text-font': ['Arial', 'sans-serif'], 'text-allow-overlap': false, 'text-ignore-placement': false }, paint: { 'text-color': '#fff0d2', 'text-halo-color': '#141413', 'text-halo-width': 1.5, 'text-opacity': 0.96 } })
      map.addLayer({ id: 'shared-network', type: 'line', source: 'output', filter: ['in', ['get', 'id'], ['literal', data.sharedRawIds]], layout: { visibility: 'none' }, paint: { 'line-color': '#faf9f5', 'line-opacity': 0.96, 'line-width': 3.5, 'line-dasharray': [1, 1] } })
      map.addLayer({ id: 'existing-chambers', type: 'circle', source: 'input', filter: ['==', ['get', 'object_type'], 'heat_chamber'], paint: { 'circle-color': '#87867f', 'circle-radius': 5, 'circle-stroke-color': '#e3dacc', 'circle-stroke-width': 1.4 } })
      map.addLayer({ id: 'new-chambers', type: 'circle', source: 'output', filter: ['==', ['get', 'object_type'], 'heat_chamber'], paint: { 'circle-color': '#d97757', 'circle-radius': 7, 'circle-stroke-color': '#faf9f5', 'circle-stroke-width': 2 } })
      map.addLayer({ id: 'consumers', type: 'circle', source: 'input', filter: ['==', ['get', 'object_type'], 'oks_connection_point'], paint: { 'circle-color': '#faf9f5', 'circle-radius': 5, 'circle-stroke-color': '#141413', 'circle-stroke-width': 1.2 } })
      map.addLayer({ id: 'source', type: 'circle', source: 'input', filter: ['==', ['get', 'object_type'], 'source'], paint: { 'circle-color': '#e3dacc', 'circle-radius': 8, 'circle-stroke-color': '#141413', 'circle-stroke-width': 2 } })
      map.addLayer({ id: 'unconnected-consumers', type: 'circle', source: 'input', filter: ['all', ['==', ['get', 'object_type'], 'oks_connection_point'], ['==', ['get', 'status'], 'unconnected']], paint: { 'circle-color': '#d97757', 'circle-radius': 7, 'circle-opacity': 0.96, 'circle-stroke-color': '#faf9f5', 'circle-stroke-width': 2 } })
      map.addLayer({ id: 'source-labels', type: 'symbol', source: 'labels', filter: ['==', ['get', 'labelKind'], 'source'], layout: { visibility: 'none', 'text-field': ['get', 'label'], 'text-size': 13, 'text-offset': [0, 1.25], 'text-anchor': 'top', 'text-font': ['Arial', 'sans-serif'] }, paint: { 'text-color': '#faf9f5', 'text-halo-color': '#141413', 'text-halo-width': 1.5 } })
      map.addLayer({ id: 'point-labels', type: 'symbol', source: 'labels', filter: ['in', ['get', 'labelKind'], ['literal', ['existing_chamber', 'new_chamber', 'consumer']]], layout: { visibility: 'none', 'text-field': ['get', 'label'], 'text-size': 11, 'text-offset': [0, 1.25], 'text-anchor': 'top', 'text-font': ['Arial', 'sans-serif'], 'text-allow-overlap': false }, paint: { 'text-color': '#e3dacc', 'text-halo-color': '#141413', 'text-halo-width': 1.2 } })
      map.addLayer({ id: 'network-labels', type: 'symbol', source: 'labels', filter: ['==', ['get', 'labelKind'], 'network'], layout: { visibility: 'none', 'symbol-placement': 'line', 'text-field': ['get', 'label'], 'text-size': 10, 'text-font': ['Arial', 'sans-serif'], 'text-allow-overlap': false, 'symbol-spacing': 250 }, paint: { 'text-color': '#faf9f5', 'text-halo-color': '#141413', 'text-halo-width': 1.2 } })
      map.on('click', (event: MapMouseEvent) => {
        const hits = map.queryRenderedFeatures(event.point, { layers: ['new-network', 'existing-network', 'new-chambers', 'existing-chambers', 'unconnected-consumers', 'consumers', 'source', 'restriction-line'] })
        if (hits[0]) {
          const feature = hits[0] as unknown as Feature
          setSelected(feature)
        } else {
          setSelected(null)
        }
      })
      map.on('mousemove', event => {
        const hits = map.queryRenderedFeatures(event.point, { layers: ['new-network', 'existing-network', 'new-chambers', 'existing-chambers', 'unconnected-consumers', 'consumers', 'source', 'restriction-line'] })
        map.getCanvas().style.cursor = hits.length ? 'pointer' : ''
        if (!hits.length) {
          setHovered(null)
          setHoverPoint(null)
          return
        }
        const hit = hits[0] as unknown as Feature
        const canvas = map.getCanvas()
        const point = map.project(event.lngLat)
        setHovered(hit)
        setHoverPoint({
          x: Math.max(18, Math.min(canvas.clientWidth - 18, point.x)),
          y: Math.max(54, Math.min(canvas.clientHeight - 18, point.y)),
        })
      })
      map.on('mouseout', () => {
        map.getCanvas().style.cursor = ''
        setHovered(null)
        setHoverPoint(null)
      })
      fit()
    })
    return () => { map.remove(); mapRef.current = null }
  }, [Boolean(data)])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !data || !map.isStyleLoaded()) return
    const vis = (id: string, on: boolean) => { if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', on ? 'visible' : 'none') }
    vis('existing-network', layers.existingNetwork); vis('new-network', layers.newNetwork); vis('new-glow', layers.newNetwork)
    vis('flow-network', layers.flow && layers.newNetwork); vis('flow-arrows', layers.flow && layers.newNetwork); vis('flow-labels', layers.flow && layers.newNetwork); vis('existing-chambers', layers.existingChambers); vis('new-chambers', layers.newChambers)
    vis('consumers', layers.consumers); vis('unconnected-consumers', layers.consumers); vis('oks-fill', layers.oks); vis('oks-outline', layers.oks)
    vis('restriction-line', layers.restrictions); vis('source', layers.source); vis('shared-network', layers.shared)
    vis('source-labels', layers.labels && layers.source); vis('point-labels', layers.labels); vis('network-labels', layers.labels)
    if (map.getLayer('new-network')) map.setPaintProperty('new-network', 'line-opacity', layers.shared ? 0.42 : 0.96)
    if (map.getLayer('new-glow')) map.setPaintProperty('new-glow', 'line-opacity', layers.shared ? 0.05 : 0.13)
  }, [layers, data])

  useEffect(() => {
    const map = mapRef.current
    if (!map || !data || !map.isStyleLoaded()) return
    const source = map.getSource('output') as maplibregl.GeoJSONSource | undefined
    if (!source) return
    const { output } = sourceData(data)
    source.setData(asGeoJSON(output.features) as any)
    const inputSource = map.getSource('input') as maplibregl.GeoJSONSource | undefined
    inputSource?.setData(asGeoJSON(mappedInput(data)) as any)
    const labelsSource = map.getSource('labels') as maplibregl.GeoJSONSource | undefined
    labelsSource?.setData(asGeoJSON(data.engineeringLabels.features) as any)
    if (map.getLayer('shared-network')) map.setFilter('shared-network', ['in', ['get', 'id'], ['literal', data.sharedRawIds]])
    setSelected(null)
    window.setTimeout(fit, 80)
  }, [data])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setPresentation(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  if (loading) return <div className="loading-screen"><div className="loader"/><span>Загрузка схемы теплосети</span></div>
  if (error || !data) return <div className="loading-screen"><strong>Не удалось загрузить сцену</strong><span>{error}</span></div>

  const p = selected?.properties ?? {}
  const selectedIsNewChamber = selected?.properties?.object_type === 'heat_chamber' && data.newChamberIds.has(featureId(selected))
  const selectedIsExistingChamber = selected?.properties?.object_type === 'heat_chamber' && data.existingChamberIds.has(featureId(selected))
  const chamberKind = selectedIsNewChamber ? data.newChamberKinds.get(featureId(selected)) : undefined
  const chamberStat = selected?.properties?.object_type === 'heat_chamber' ? data.chamberStats.get(featureId(selected)) : undefined
  const segmentStat = selected?.properties?.object_type === 'heat_network' ? data.segmentStats.get(featureId(selected)) : undefined
  const selectedDiagnostic = selected?.properties?.object_type === 'oks_connection_point' ? data.diagnostics.get(featureId(selected)) : undefined
  const selectedUnconnected = selected?.properties?.object_type === 'oks_connection_point' ? unconnectedInfo(data.summary, p.id) : null
  const selectedIsUnconnected = selectedUnconnected !== null
  const restrictionTypes = data.inputSummary.restrictionTypes
  const setLayer = (key: LayerKey, value: boolean) => setLayers(current => ({ ...current, [key]: value }))
  const hoveredProperties = hovered?.properties ?? {}
  const hoveredType = String(hoveredProperties.object_type ?? '')
  const hoveredIsNewChamber = hoveredType === 'heat_chamber' && data.newChamberIds.has(featureId(hovered as Feature))
  const hoveredChamberKind = hoveredIsNewChamber ? data.newChamberKinds.get(featureId(hovered as Feature)) : undefined
  const hoveredSegmentStat = hoveredType === 'heat_network' ? data.segmentStats.get(featureId(hovered as Feature)) : undefined
  const hoveredChamberStat = hoveredType === 'heat_chamber' ? data.chamberStats.get(featureId(hovered as Feature)) : undefined
  const hoveredTitle = hoveredType === 'heat_chamber'
    ? hoveredIsNewChamber ? `${data.newChamberAliases.get(featureId(hovered as Feature)) ?? 'Новая камера'}` : `Камера ${hoveredProperties.id ?? '—'}`
    : humanObject(hoveredType)
  const diagnosticReason = (diagnostic: Diagnostic) => {
    const nearest = Number(diagnostic.proof.nearest_distance_m)
    const reentry = Number(diagnostic.proof.first_reentry_m)
    return `Ближайшая граница ${fmtNum(nearest, 2)} м · повторный вход ${fmtNum(reentry, 2)} м`
  }

  return <main className={presentation ? 'app presentation' : 'app'}>
    <div ref={nodeRef} className="map"/>
    <div className="vignette"/>
    <header className="topbar">
      <div className="title-block"><b>Схема теплосети</b><small>{policyCopy(policy).label} · интерактивная карта подключения</small></div>
      <div className="policy-switch" aria-label="Трактовка терминального правила"><button className={policy === 'strict' ? 'active' : ''} onClick={() => setPolicy('strict')}>Строгая</button><button className={policy === 'alternative' ? 'active' : ''} onClick={() => setPolicy('alternative')}>Альтернативная</button></div>
      <button className="engineering-button" onClick={() => setLayer('labels', !layers.labels)}>{layers.labels ? 'Презентационный режим' : 'Инженерный режим'}</button>
      <button className="presentation-button" onClick={() => { setPresentation(value => !value); window.setTimeout(fit, 50) }}>{presentation ? 'Выйти' : 'Режим презентации'}</button>
    </header>
    {policyCopy(policy).disclosure && <div className="policy-disclosure">{policyCopy(policy).disclosure}</div>}
    <section className="kpis">
      <div><span>Подключение</span><strong>{metrics.coverage}</strong><em>{metrics.missing ? `${metrics.missing} штрафа за недоступность` : 'Все потребители подключены'}</em></div>
      <div><span>Оценка</span><strong>{fmtNum(metrics.score, 3)}</strong></div>
      <div><span>Стоимость</span><strong>{fmtMoney(metrics.cost)}</strong></div>
      <div><span>Новая сеть</span><strong>{fmtNum(metrics.length / 1000, 3)} км</strong></div>
      <div className="validation"><span>Проверка</span><strong>ПРОЙДЕНО</strong><em>Внутренняя проверка пройдена</em></div>
    </section>
    <aside className="left-panel">
      <div className="panel-head"><span>Слои и легенда</span><span className="live-dot">АКТИВНО</span></div>
      {(Object.keys(label) as LayerKey[]).map(key => <label className="toggle" key={key}><span>{label[key]}</span><input type="checkbox" checked={layers[key]} onChange={event => setLayer(key, event.target.checked)}/><i/></label>)}
      <div className="legend"><b>Диаметр трубопровода DN</b><span><i className="swatch cyan"/>65</span><span><i className="swatch blue"/>100</span><span><i className="swatch violet"/>125</span><span><i className="swatch amber"/>150</span><span><i className="swatch coral"/>200+</span></div>
      <div className="legend object-legend"><b>Объекты карты</b><span><i className="dot existing"/>Существующая сеть и камеры</span><span><i className="dot new"/>Новая сеть и камеры</span><span><i className="dot consumer"/>Потребители</span><span><i className="dot building"/>Здания ОКС</span><span><i className="dot restriction"/>Ограничения</span><span><i className="dot source"/>Источник</span></div>
      {restrictionTypes.length > 0 && <div className="legend restrictions-legend"><b>Типы ограничений</b>{restrictionTypes.map(item => <span key={item.type}>{item.type} · {item.count}</span>)}</div>}
      <button className="ghost" onClick={() => { setPresentation(false); fit() }}>⌖ К сети</button><button className="ghost" onClick={() => mapRef.current?.easeTo({ pitch: 0, bearing: 0, duration: 700 })}>Вид сверху</button><button className="ghost" onClick={() => mapRef.current?.easeTo({ pitch: 48, bearing: -15, duration: 700 })}>3D‑вид</button>
    </aside>
    <aside className={`right-panel ${selected ? 'selected-panel' : 'overview-panel'}`}>
      {selected ? <>
        <div className="panel-head"><span>Выбранный объект</span><button className="close" onClick={() => setSelected(null)} aria-label="Закрыть">×</button></div>
        <h2>{humanObject(p.object_type)}</h2>
        <div className="property-list">
          <Row k="ID" v={p.id}/>
          <Row k="Тип" v={chamberKind ? humanChamber(chamberKind) : selectedIsExistingChamber ? 'Существующая камера' : humanObject(p.object_type)}/>
          <Row k="DN" v={p.diameter ? `${p.diameter} мм` : undefined}/>
          <Row k="Расход" v={p.flow_tph ? `${p.flow_tph} т/ч` : undefined}/>
          <Row k="Длина" v={p.length ? `${Number(p.length).toFixed(1)} м` : undefined}/>
          <Row k="Стоимость" v={p.cost ? fmtMoney(Number(p.cost)) : undefined}/>
          <Row k="От узла" v={p.start_node_id}/><Row k="До узла" v={p.end_node_id}/>
          {p.object_type === 'heat_network' && <><Row k="Структура" v={segmentStat?.shared ? `Общая магистраль · ${segmentStat.consumers.length} потребит.` : 'Отдельная ветвь'}/><Row k="Суммарный расход" v={segmentStat?.aggregatedFlow ? `${segmentStat.aggregatedFlow.toFixed(1)} т/ч` : undefined}/></>}
          {p.object_type === 'heat_chamber' && <><Row k="Примыканий" v={chamberStat?.incidentCount}/><Row k="Максимальный DN" v={chamberStat?.maxDiameter ? `${chamberStat.maxDiameter} мм` : undefined}/></>}
          {p.object_type === 'restriction' && <><Row k="Тип ограничения" v={p.restriction_type}/><Row k="Адрес" v={p.address}/></>}
          {p.object_type === 'oks_connection_point' && <Row k="Статус" v={humanStatus(selectedIsUnconnected ? 'unconnected' : 'connected')}/>} 
        </div>
        {chamberKind === 'tie-in' && <div className="explain-note">Камера присоединения — место подключения новой сети к существующей.</div>}
        {chamberKind === 'branch' && <div className="explain-note">Камера разветвления — место разделения новой сети на ветви.</div>}
        {selectedIsUnconnected && selectedDiagnostic && <div className="notice">Допустимый терминальный подход по строгому правилу ближайшей границы не найден; применён предусмотренный ТЗ штраф.</div>}
      </> : <>
        <div className="panel-head"><span>Обзор решения</span><span className="signal">●</span></div><h2>Общие магистрали</h2><p className="muted">Общие участки определяются по топологии и расчётному расходу.</p><div className="insight"><strong>{data.sharedIds.size}</strong><span>общих участков сети</span></div>
        <button className="score-card" onClick={() => setScoreOpen(value => !value)}><span>Как считается оценка</span><b>{scoreOpen ? '−' : '+'}</b>{scoreOpen && <div className="formula">S = 0,7 × (C / 25 000 000) + 0,3 × (L / 100)<br/><small>C {fmtMoney(metrics.cost)} · L {fmtNum(metrics.length, 1)} м · S {fmtNum(metrics.score, 3)}</small></div>}</button>
        <div className="compare"><span>Вариант v2 того же policy</span><b>S {fmtNum(baselineScore, 3)} · {fmtNum(baselineLength / 1000, 3)} км</b><span>Показанный вариант</span><b>S {fmtNum(metrics.score, 3)} · {fmtNum(metrics.length / 1000, 3)} км</b><em>−{fmtNum(scoreImprovement, 1)}% оценки · −{fmtNum(lengthReduction, 3)} км</em></div>
      </>}
    </aside>
    {selectedUnconnected && <aside className="diagnostic-card"><div><b>Почему ОКС {String(p.id)} не подключён</b><span>Не подключён · {selectedUnconnected.policy === 'literal' ? 'strict/literal' : selectedUnconnected.policy}</span></div><p>{selectedUnconnected.reason} Применён штраф по §2.5 / §6. Relaxed — отдельная альтернативная трактовка; её результат и метрики не смешиваются со strict.</p>{selectedDiagnostic && <p>{diagnosticReason(selectedDiagnostic)}.</p>}</aside>}
    {hovered && hoverPoint && <div className="map-tooltip" style={{ left: hoverPoint.x, top: hoverPoint.y }}><strong>{hoveredTitle}</strong><span className="tooltip-id">ID {String(hoveredProperties.id ?? '—')}</span>{hoveredType === 'heat_network' && <><span>ДУ {hoveredProperties.diameter ? `${hoveredProperties.diameter} мм` : '—'}{hoveredProperties.flow_tph ? ` · ${Number(hoveredProperties.flow_tph).toFixed(1)} т/ч` : ''}</span>{hoveredProperties.length !== undefined && <span>{Number(hoveredProperties.length).toFixed(1)} м{hoveredSegmentStat?.shared ? ` · ${hoveredSegmentStat.consumers.length} потребит.` : ''}</span>}</>}{hoveredType === 'heat_chamber' && <><span>{hoveredChamberKind ? humanChamber(hoveredChamberKind) : 'Существующая камера'}</span>{hoveredChamberStat && <span>{hoveredChamberStat.incidentCount} примык. · DN {hoveredChamberStat.maxDiameter ?? '—'}</span>}</>}{hoveredType === 'oks_connection_point' && <span>{humanStatus(hoveredProperties.status)}</span>}{hoveredType === 'restriction' && <span>{String(hoveredProperties.restriction_type ?? 'Ограничение')}</span>}{hoveredType === 'source' && <span>{String(hoveredProperties.name ?? '')}</span>}</div>}
  </main>
}

function Row({ k, v }: { k: string; v: unknown }) { return v === undefined || v === null || v === '' ? null : <div className="row"><span>{k}</span><b>{String(v)}</b></div> }

createRoot(document.getElementById('root')!).render(<App />)
