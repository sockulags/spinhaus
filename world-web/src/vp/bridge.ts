// Spinhaus replacement for VeloPopuli's Tauri bridge: the renderer fetches mesh buffers
// from /api/<name>, which the Android app (or the desktop preview server) answers.

async function bytes(name: string, a = 0, b = 0): Promise<ArrayBuffer> {
  const res = await fetch(`/api/${name}/${a}/${b}`)
  if (!res.ok) throw new Error(`${name}: ${res.status} ${await res.text()}`)
  return res.arrayBuffer()
}

declare global {
  interface Window {
    SpinhausAndroid?: { log(msg: string): void; ready(json: string): void; error(msg: string): void; stats(json: string): void }
  }
}

export const api = {
  ride: {
    routeData: () => bytes('route'),
    roadMesh: () => bytes('road'),
    structures: () => bytes('structures'),
    barriers: () => bytes('barriers'),
    trees: () => bytes('trees'),
    chunk: (cx: number, cz: number) => bytes('chunk', cx, cz),
    far: (cx: number, cz: number) => bytes('far', cx, cz)
  },
  log: async (msg: string): Promise<void> => {
    console.log(msg)
    window.SpinhausAndroid?.log(msg)
  }
}

export function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}
