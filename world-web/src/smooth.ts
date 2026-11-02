/** Smooth, never-backwards rider position from sparse distance reports.
 *  The app reports a new distance a few times a second; between reports the rider keeps moving
 *  at the reported speed, and differences are corrected by easing the pace (±15 %), not by
 *  jumping back. Only big differences (start, skips) snap. */
export class RiderSmoother {
  shown = 0
  private lastReported = Number.NaN
  private reportedAt = 0

  update(nowMs: number, dt: number, distance: number, speed: number, moving: boolean, receivedAtMs: number): number {
    if (distance !== this.lastReported) {
      this.lastReported = distance
      this.reportedAt = receivedAtMs
    }
    const target = distance + (moving ? speed * Math.min(0.5, (nowMs - this.reportedAt) / 1000) : 0)
    const error = target - this.shown
    if (Math.abs(error) > 25) {
      this.shown = target
    } else if (moving) {
      const nudge = Math.max(-0.15, Math.min(0.15, error / Math.max(2, 2 * speed)))
      this.shown += Math.max(0, speed * (1 + nudge)) * dt
    }
    return this.shown
  }
}
