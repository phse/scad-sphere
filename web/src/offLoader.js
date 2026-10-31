// OFF (Object File Format), wie OpenSCAD es schreibt: Kopfzeile „OFF“ (auch mit den Anzahlen in derselben Zeile),
// Ecken, dann Flächen als „n i1 … in [r g b [a]]“. Farben je Fläche (0-255 oder 0-1) werden zu Eckfarben.
// Varianten mit Normalen, Texturkoordinaten oder Farben je Ecke (NOFF, STOFF, COFF) lesen die Koordinaten mit.
import { BufferGeometry, Color, Float32BufferAttribute, SRGBColorSpace } from 'three'

/**
 * @param {Color} fallbackColor für Flächen ohne Farbe, wenn andere eine haben
 * @returns {{ geometry: BufferGeometry, hasColors: boolean }}
 */
export function parseOff(text, fallbackColor) {
  const lines = []
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.replace(/#.*/, '').trim()
    if (line) lines.push(line.split(/\s+/))
  }
  if (lines.length === 0) throw new Error('Empty OFF file')
  const header = lines[0][0]
  if (!/OFF$/i.test(header)) throw new Error(`Not an OFF file (${header})`)
  let counts = lines[0].slice(1)
  let index = 1
  if (counts.length < 2) counts = lines[index++] || []
  const vertexCount = parseInt(counts[0], 10)
  const faceCount = parseInt(counts[1], 10)
  if (!(vertexCount >= 0) || !(faceCount >= 0)) throw new Error('Invalid OFF counts')

  const vertices = new Float32Array(vertexCount * 3)
  for (let i = 0; i < vertexCount; i++) {
    const values = lines[index++]
    if (!values) throw new Error('OFF file ends early')
    vertices[i * 3] = parseFloat(values[0])
    vertices[i * 3 + 1] = parseFloat(values[1])
    vertices[i * 3 + 2] = parseFloat(values[2])
  }

  const positions = []
  const colors = []
  let hasColors = false
  const color = new Color()
  for (let f = 0; f < faceCount; f++) {
    const values = lines[index++]
    if (!values) break
    const n = parseInt(values[0], 10)
    if (!(n >= 3)) continue
    const ids = values.slice(1, 1 + n).map((v) => parseInt(v, 10))
    const rest = values.slice(1 + n).map(parseFloat)
    if (rest.length >= 3) {
      // Ganze Zahlen: 0-255, sonst 0-1. OFF-Farben sind sRGB, three.js rechnet linear.
      const scale = values.slice(1 + n, 4 + n).every((v) => /^\d+$/.test(v)) ? 1 / 255 : 1
      color.setRGB(rest[0] * scale, rest[1] * scale, rest[2] * scale, SRGBColorSpace)
      hasColors = true
    } else {
      color.copy(fallbackColor)
    }
    // Vielecke als Fächer in Dreiecke zerlegen
    for (let k = 1; k < n - 1; k++) {
      for (const id of [ids[0], ids[k], ids[k + 1]]) {
        positions.push(vertices[id * 3], vertices[id * 3 + 1], vertices[id * 3 + 2])
        colors.push(color.r, color.g, color.b)
      }
    }
  }

  const geometry = new BufferGeometry()
  geometry.setAttribute('position', new Float32BufferAttribute(positions, 3))
  if (hasColors) geometry.setAttribute('color', new Float32BufferAttribute(colors, 3))
  geometry.computeVertexNormals()
  return { geometry, hasColors }
}
