import test from 'node:test'
import assert from 'node:assert/strict'
import { adaptScene, unconnectedInfo } from '../.policy-test/data.js'

const fc = features => ({ type: 'FeatureCollection', features })
const feature = (properties, geometry = null) => ({ type: 'Feature', properties, geometry })
const point = coordinates => ({ type: 'Point', coordinates })
const input = fc([feature({ id: 'existing', object_type: 'heat_network' }, { type: 'LineString', coordinates: [[0, 0], [1, 0]] })])
const scene = chamber => adaptScene(fc([chamber]), fc([]), input)

test('backend tie-in flag overrides distance, including explicit false', () => {
  assert.equal(scene(feature({ id: 'far', object_type: 'heat_chamber', diag_tie_in: true }, point([5, 5]))).newChamberKinds.get('string:far'), 'tie-in')
  assert.equal(scene(feature({ id: 'near', object_type: 'heat_chamber', diag_tie_in: false }, point([0.5, 0]))).newChamberKinds.get('string:near'), 'branch')
})

test('legacy chambers without backend flag retain distance fallback', () => {
  assert.equal(scene(feature({ id: 'legacy', object_type: 'heat_chamber' }, point([0.5, 0]))).newChamberKinds.get('string:legacy'), 'tie-in')
})

test('unconnected explanation comes from summary without optional proof', () => {
  const summary = { diag_terminal_policy: 'literal', unconnected_oks_ids: ['hidden-target', 71], diag_unconnected_reasons: { 'hidden-target': 'blocked by park', '71': 'no terminal exit' } }
  assert.deepEqual(unconnectedInfo(summary, 'hidden-target'), { policy: 'literal', reason: 'blocked by park' })
  assert.equal(unconnectedInfo(summary, 71).reason, 'no terminal exit')
  assert.equal(unconnectedInfo(summary, '71'), null)
  assert.equal(unconnectedInfo(summary, 'connected'), null)
})

test('missing diagnostic reason still explains unconnected status', () => {
  assert.match(unconnectedInfo({ unconnected_oks_ids: ['unknown'], diag_terminal_policy: 'relaxed' }, 'unknown').reason, /маршрут/i)
})
