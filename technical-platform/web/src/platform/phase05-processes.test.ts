import { describe, expect, it } from 'vitest'
import { PHASE05_CODES, PHASE05_PROCESSES } from './phase05-processes'

describe('PHASE-05 process catalog', () => {
  it('contains exactly P016 through P020 in construction order', () => {
    expect(PHASE05_CODES).toEqual(['P016', 'P017', 'P018', 'P019', 'P020'])
  })

  it('preserves source-derived state counts and primary tables', () => {
    expect(PHASE05_PROCESSES.map((process) => [process.code, process.states.length, process.primaryTable])).toEqual([
      ['P016', 8, 'welfare.care_case'],
      ['P017', 9, 'document.signature_envelope'],
      ['P018', 11, 'integration.data_import_job'],
      ['P019', 9, 'audit.data_export_request'],
      ['P020', 9, 'audit.data_quality_issue'],
    ])
  })

  it('surfaces the mandatory high-risk gates', () => {
    const risks = Object.fromEntries(PHASE05_PROCESSES.map((process) => [process.code, process.riskGate]))
    expect(risks.P017).toContain('不可变')
    expect(risks.P019).toContain('二次认证')
    expect(risks.P020).toContain('必须不同')
  })
})
