export type Policy = 'strict' | 'alternative'

export type PolicyCopy = {
  label: string
  disclosure: string
  outputPath: string
}

export function policyCopy(policy: Policy): PolicyCopy {
  if (policy === 'alternative') {
    return {
      label: 'Альтернативная трактовка',
      disclosure: 'Использует альтернативную трактовку терминального выхода; см. TERMINAL_POLICY_DECISION.md.',
      outputPath: '/data/alternative.geojson',
    }
  }
  return {
    label: 'Строгая трактовка',
    disclosure: '',
    outputPath: '/data/strict.geojson',
  }
}

export function variantCollection<T extends { features: Array<{ properties?: { variant_id?: string } }> }>(collection: T, variantId: string): T {
  return { ...collection, features: collection.features.filter(feature => feature.properties?.variant_id === variantId) }
}
