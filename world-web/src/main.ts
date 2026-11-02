// Spinhaus world view: draws VeloPopuli's world for the route the app prepared and moves the
// rider to the distance the app reports. The app owns the ride; this page only draws.
import './style.css'
import type { Route } from './vp/core/gpx'
import type { RideInit } from './vp/types'
import { World } from './vp/world/scene'
import { look, timeFromHour } from './vp/world/weather'
import type { TimeOfDay, Weather } from './vp/types'
import { api, errorText } from './vp/bridge'

interface AppState {
  distance: number
  speed: number
  cadence: number
  moving: boolean
}

declare global {
  interface Window {
    spinhaus: {
      setState(s: AppState): void
      cycleCamera(): void
      setLook(time: string, weather: string): void
    }
  }
}

const canvas = document.getElementById('world') as HTMLCanvasElement
const status = document.getElementById('status')!

let world: World | null = null
let latest: { s: AppState; at: number } | null = null
let init: RideInit | null = null

window.spinhaus = {
  setState(s) {
    latest = { s, at: performance.now() }
  },
  cycleCamera() {
    world?.cycleCamera()
  },
  setLook(time, weather) {
    if (!world || !init) return
    const t = time === 'clock' ? timeFromHour(init.hour) : (time as Exclude<TimeOfDay, 'clock'>)
    world.setLook(look(t, weather as Exclude<Weather, 'live'>))
  }
}

async function start(): Promise<void> {
  init = (await (await fetch('/api/init')).json()) as RideInit
  const data = await api.ride.routeData()
  const n = init.n
  const block = (i: number): Float64Array => new Float64Array(data, i * n * 8, n)
  const route: Route = {
    name: init.name,
    step: init.step,
    n,
    total: init.total,
    x: block(0),
    z: block(1),
    lat: block(2),
    lon: block(3),
    ele: block(4),
    grade: block(5),
    width: data.byteLength >= n * 56 ? block(6) : new Float64Array(n).fill(6.5),
    bank: data.byteLength >= n * 64 ? block(7) : new Float64Array(n),
    dip: data.byteLength >= n * 72 ? block(8) : new Float64Array(n),
    climbs: init.climbs
  }
  await document.fonts.load('800 60px Manrope').catch(() => undefined)
  const w = new World(canvas, route, init.layout, init.covered, (i) => `${i} km`, 1000, init.landmarks)
  world = w
  await w.load()
  w.setLook(look(timeFromHour(init.hour), 'clear'))
  const resize = (): void => w.resize(canvas.clientWidth, canvas.clientHeight)
  new ResizeObserver(resize).observe(canvas)
  resize()
  status.textContent = ''
  window.SpinhausAndroid?.ready(JSON.stringify({ total: init.total }))

  let last = performance.now()
  const recent: number[] = []
  let lastStats = performance.now()
  let failed = false
  const loop = (now: number): void => {
    requestAnimationFrame(loop)
    const dt = Math.min(0.1, (now - last) / 1000)
    last = now
    const s = latest?.s ?? { distance: 0, speed: 0, cadence: 0, moving: false }
    const ahead = latest && s.moving ? Math.min(0.3, (now - latest.at) / 1000) : 0
    try {
      w.frame(dt, { distance: Math.min(route.total, s.distance + s.speed * ahead), speed: s.speed, cadence: s.cadence, rivals: [] })
    } catch (err) {
      if (!failed) void api.log(`frame failed: ${errorText(err)}`)
      failed = true
    }
    recent.push(now)
    if (recent.length > 120) recent.shift()
    if (now - lastStats > 3000 && recent.length > 10) {
      lastStats = now
      const fps = (1000 * (recent.length - 1)) / (recent[recent.length - 1] - recent[0])
      const d = w.drawn()
      const stats = { fps: Math.round(fps), triangles: d.triangles, calls: d.calls }
      ;(window as unknown as { __stats: unknown }).__stats = stats
      window.SpinhausAndroid?.stats(JSON.stringify(stats))
    }
  }
  requestAnimationFrame(loop)

  // Desktop preview without the app: ride along on our own at 25 km/h.
  if (!window.SpinhausAndroid && location.search.includes('demo')) {
    let d = Number(new URLSearchParams(location.search).get('at') ?? 0)
    setInterval(() => {
      d += 7 * 0.1
      window.spinhaus.setState({ distance: d, speed: 7, cadence: 88, moving: true })
    }, 100)
  }
}

start().catch((err) => {
  status.textContent = errorText(err)
  window.SpinhausAndroid?.error(errorText(err))
})
