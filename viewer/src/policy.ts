export type Policy = 'main' | 'strict'

export type PolicyCopy = {
  label: string
  disclosure: string
  outputPath: string
}

export function policyCopy(policy: Policy): PolicyCopy {
  if (policy === 'strict') {
    return {
      label: 'Строгое прочтение §2.2',
      disclosure: 'Для сравнения: ближайшая точка всей границы здания, включая стены дворов; точки с закрытым выходом остаются неподключёнными.',
      outputPath: '/data/strict.geojson',
    }
  }
  return {
    label: 'Основной режим',
    disclosure: '',
    outputPath: '/data/main.geojson',
  }
}

export function variantCollection<T extends { features: Array<{ properties?: { variant_id?: string } }> }>(collection: T, variantId: string): T {
  return { ...collection, features: collection.features.filter(feature => feature.properties?.variant_id === variantId) }
}
