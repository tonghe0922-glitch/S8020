import { describe, expect, it } from 'vitest'
import { eventValue } from './eventValue'

describe('PHASE-07 form event contract', () => {
  it('normalizes valid form event values', () => {
    const event = { target: { value: '财神谷' } } as unknown as Event
    expect(eventValue(event)).toBe('财神谷')
  })

  it('fails closed for missing or non-string values', () => {
    expect(eventValue({ target: null } as unknown as Event)).toBe('')
    expect(eventValue({ target: { value: 42 } } as unknown as Event)).toBe('')
  })
})
