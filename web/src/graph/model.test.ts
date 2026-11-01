import { describe, expect, it } from 'vitest'
import { buildModel, outsOf } from './model'
import type { JourneyVersion } from '../api/types'

describe('outsOf', () => {
  it('null、缺漏、非物件都當空物件', () => {
    expect(outsOf({ outputs: null })).toEqual({})
    expect(outsOf({})).toEqual({})
    expect(outsOf(null)).toEqual({})
    expect(outsOf({ outputs: { next: 'a' } })).toEqual({ next: 'a' })
  })
})

describe('buildModel', () => {
  it('終端節點 outputs 為 null 不丟例外', () => {
    const def = {
      entry_node: 's',
      layout: null,
      nodes: [
        { id: 's', type: 'StartNode', name: null, config: null, outputs: { next: 'x' } },
        { id: 'x', type: 'EndNode', name: '結束', config: null, outputs: null },
      ],
    } as unknown as JourneyVersion
    const m = buildModel(def, null)
    expect(m.nodes).toHaveLength(2)
    expect(m.edges).toHaveLength(1)
  })
})
