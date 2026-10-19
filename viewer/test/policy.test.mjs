import test from 'node:test'
import assert from 'node:assert/strict'
import { policyCopy, variantCollection } from '../.policy-test/policy.js'

test('main is the default policy copy without disclosure', () => {
  assert.deepEqual(policyCopy('main'), {
    label: 'Основной режим',
    disclosure: '',
    outputPath: '/data/main.geojson',
  })
})

test('strict comparison copy carries an explicit disclosure', () => {
  const copy = policyCopy('strict')
  assert.equal(copy.label, 'Строгое прочтение §2.2')
  assert.equal(copy.outputPath, '/data/strict.geojson')
  assert.match(copy.disclosure, /сравнени/i)
})

test('variant selection never mixes v1 and v2 geometry or summaries', () => {
  const full = { type: 'FeatureCollection', features: [
    { type: 'Feature', properties: { object_type: 'heat_network', variant_id: 'v1' }, geometry: null },
    { type: 'Feature', properties: { object_type: 'variant_summary', variant_id: 'v1', score: 1 }, geometry: null },
    { type: 'Feature', properties: { object_type: 'heat_network', variant_id: 'v2' }, geometry: null },
    { type: 'Feature', properties: { object_type: 'variant_summary', variant_id: 'v2', score: 2 }, geometry: null },
  ] }
  const selected = variantCollection(full, 'v2')
  assert.equal(selected.features.length, 2)
  assert.ok(selected.features.every(feature => feature.properties.variant_id === 'v2'))
})

