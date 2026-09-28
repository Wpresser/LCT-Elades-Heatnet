import test from 'node:test'
import assert from 'node:assert/strict'
import { policyCopy, variantCollection } from '../.policy-test/policy.js'

test('strict is the submission-safe default policy copy', () => {
  assert.deepEqual(policyCopy('strict'), {
    label: 'Строгая трактовка',
    disclosure: '',
    outputPath: '/data/strict.geojson',
  })
})

test('alternative policy copy carries an explicit disclosure', () => {
  const copy = policyCopy('alternative')
  assert.equal(copy.label, 'Альтернативная трактовка')
  assert.equal(copy.outputPath, '/data/alternative.geojson')
  assert.match(copy.disclosure, /альтернативн/i)
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

