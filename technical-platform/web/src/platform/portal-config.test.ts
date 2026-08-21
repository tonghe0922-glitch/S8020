import { describe, expect, it } from 'vitest'
import { PORTALS } from './portal-config'

describe('portal definitions', () => {
  it('defines exactly the work and tech ports', () => {
    expect(Object.keys(PORTALS).sort()).toEqual(['tech', 'work'])
    expect(PORTALS.work.entry).toBe('work')
    expect(PORTALS.tech.entry).toBe('tech')
  })

  it('keeps employee and center responsibilities inside the work port', () => {
    expect(PORTALS.work.description).toContain('员工本人业务和中心管理业务')
    expect(PORTALS.work.homeTitle).toBe('我的工作台')
  })

  it('does not model the tech port as a business super administrator', () => {
    expect(PORTALS.tech.description).toContain('不代表业务超级管理员')
  })

  it('keeps runtime descriptions free of engineering phase evidence', () => {
    const serialized = JSON.stringify(PORTALS)
    expect(serialized).not.toMatch(/PHASE|阶段\s*\d+/i)
    expect(serialized).not.toMatch(/\bP\d{3}\b/)
  })
})
