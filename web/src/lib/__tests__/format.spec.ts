import { describe, expect, it } from 'vitest'
import { ago, delta, ptClock, ptTime, sparkline } from '../format'

describe('format', () => {
  it('shows times in Vancouver time whatever the viewer zone', () => {
    // 2026-10-06T21:05Z is 14:05 PDT
    expect(ptClock('2026-10-06T21:05:00Z')).toBe('14:05')
    expect(ptTime('2026-10-06T21:05:00Z')).toContain('14:05')
    // winter 2025 was still standard time (UTC-8). From 2026-11-01 tzdata 2026b keeps British Columbia on
    // UTC-7 all year, so later winter dates depend on the viewer's tz database and are not asserted here.
    expect(ptClock('2025-12-02T22:05:00Z')).toBe('14:05')
  })

  it('compares against the previous period, or says n/a before history began', () => {
    expect(delta(12, 10)).toEqual({ text: '+20%', tone: 'up' })
    expect(delta(8, 10)).toEqual({ text: '-20%', tone: 'down' })
    expect(delta(10, 10)).toEqual({ text: '±0%', tone: 'flat' })
    expect(delta(3, 0)).toEqual({ text: '+3', tone: 'up' })
    expect(delta(3, 0, true)).toEqual({ text: 'n/a', tone: 'na' })
  })

  it('formats elapsed minutes', () => {
    expect(ago(null)).toBe('never')
    expect(ago(0.2)).toBe('just now')
    expect(ago(12)).toBe('12 min ago')
    expect(ago(180)).toBe('3 h ago')
    expect(ago(3 * 1440)).toBe('3 d ago')
  })

  it('scales a sparkline to its box', () => {
    expect(sparkline([0, 10], 100, 20, 0)).toBe('0.0,20.0 100.0,0.0')
    expect(sparkline([], 100, 20)).toBe('')
  })
})
