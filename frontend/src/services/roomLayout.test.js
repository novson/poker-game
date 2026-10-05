import { describe, expect, it } from 'vitest'
import { roomLayout } from './roomLayout'

describe('room layout', () => {
  it('fits a six-seat scene between the header and action controls on small phones', () => {
    const layout = roomLayout(375, 435, true)
    expect(layout.sceneWidth).toBeLessThanOrEqual(375)
    expect(layout.sceneHeight).toBeLessThanOrEqual(435)
    expect(layout.tableHeight + 120).toBe(layout.sceneHeight)
    expect(layout.tableHeight).toBeGreaterThanOrEqual(260)
  })
  it('keeps seats readable and provides a scrollable scene when a keyboard leaves little height', () => {
    const layout = roomLayout(320, 180, true)
    expect(layout.sceneHeight).toBeGreaterThan(180)
    expect(layout.tableHeight).toBe(260)
    expect(layout.sceneWidth).toBeLessThanOrEqual(320)
  })
  it('fits a wide table in a short desktop window', () => {
    const layout = roomLayout(1280, 490, false)
    expect(layout.sceneHeight).toBeLessThanOrEqual(490)
    expect(layout.sceneWidth).toBeLessThanOrEqual(1280)
  })
})
