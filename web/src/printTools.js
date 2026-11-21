// Rechnungen für den Druck ohne three.js (auch per node testbar): Stellen, an denen eine Schicht in der Luft beginnt,
// Druckausrichtungen bewerten und Schnitte vorschlagen, wenn das Modell größer als das Druckbett ist.
// Dreiecke kommen als flache Liste [ax, ay, az, bx, ..., cz, ...] in Szenen-Koordinaten (Z oben).

/** Punkte zusammenfassen (STL wiederholt jeden Punkt je Dreieck): Kennung je Ecke und die Lage je Kennung. */
function weld(tris, size) {
  const quantum = Math.max(size * 1e-7, 1e-7)
  const ids = new Map()
  const points = []
  const corner = new Int32Array(tris.length / 3)
  for (let i = 0; i < corner.length; i++) {
    const x = tris[i * 3], y = tris[i * 3 + 1], z = tris[i * 3 + 2]
    const key = `${Math.round(x / quantum)},${Math.round(y / quantum)},${Math.round(z / quantum)}`
    let id = ids.get(key)
    if (id === undefined) {
      id = points.length / 3
      ids.set(key, id)
      points.push(x, y, z)
    }
    corner[i] = id
  }
  return { corner, points }
}

/**
 * Stellen, an denen beim Druck in +Z eine Schicht in der Luft beginnt (Inseln): Tiefpunkte der Oberfläche, an denen das
 * Material nach unten zeigt und die nicht auf dem Bett liegen - z. B. ein nach unten hängender Zapfen. Ein flacher Boden
 * zählt als ein Tiefpunkt (zusammenhängende Ecken gleicher Höhe), wenn alle Nachbarn höher liegen. Anders als ein
 * Überhang hängt eine Insel an nichts, was schon gedruckt ist: Der Slicer braucht dort Stützen.
 * Ergebnis: [{ x, y, z, r }] (Mitte der Unterseite und ihr Radius), höchstens [max].
 */
export function findIslands(tris, minZ, size, max = 50) {
  if (!tris.length) return []
  const { corner, points } = weld(tris, size)
  const count = points.length / 3
  const eps = Math.max(size * 1e-6, 1e-4)
  // Nachbarn und Normale je Ecke (Flächen gewichtet)
  const neighbors = Array.from({ length: count }, () => [])
  const normalZ = new Float64Array(count)
  const normalLength = new Float64Array(count)
  const nx = new Float64Array(count), ny = new Float64Array(count)
  for (let t = 0; t < corner.length; t += 3) {
    const a = corner[t], b = corner[t + 1], c = corner[t + 2]
    if (a === b || b === c || a === c) continue
    const ax = points[a * 3], ay = points[a * 3 + 1], az = points[a * 3 + 2]
    const ux = points[b * 3] - ax, uy = points[b * 3 + 1] - ay, uz = points[b * 3 + 2] - az
    const vx = points[c * 3] - ax, vy = points[c * 3 + 1] - ay, vz = points[c * 3 + 2] - az
    const cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx
    for (const p of [a, b, c]) { nx[p] += cx; ny[p] += cy; normalZ[p] += cz }
    neighbors[a].push(b, c)
    neighbors[b].push(a, c)
    neighbors[c].push(a, b)
  }
  for (let p = 0; p < count; p++) normalLength[p] = Math.hypot(nx[p], ny[p], normalZ[p])
  // Ebenen gleicher Höhe zusammenfassen
  const parent = new Int32Array(count).map((_, i) => i)
  const find = (i) => { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i] } return i }
  for (let p = 0; p < count; p++) {
    for (const q of neighbors[p]) {
      if (Math.abs(points[p * 3 + 2] - points[q * 3 + 2]) < eps) {
        const rp = find(p), rq = find(q)
        if (rp !== rq) parent[rp] = rq
      }
    }
  }
  const groups = new Map()
  for (let p = 0; p < count; p++) {
    const root = find(p)
    let group = groups.get(root)
    if (!group) groups.set(root, (group = []))
    group.push(p)
  }
  const islands = []
  for (const group of groups.values()) {
    const z = points[group[0] * 3 + 2]
    // Auf dem Bett (erste Schicht) ist kein Problem
    if (z - minZ < 0.1) continue
    let lowest = true
    let gx = 0, gy = 0, gz = 0
    let sx = 0, sy = 0
    const members = new Set(group)
    for (const p of group) {
      for (const q of neighbors[p]) {
        if (members.has(q)) continue
        if (points[q * 3 + 2] < z + eps) { lowest = false; break }
      }
      if (!lowest) break
      gx += nx[p]; gy += ny[p]; gz += normalZ[p]
      sx += points[p * 3]; sy += points[p * 3 + 1]
    }
    if (!lowest) continue
    // Das Material muss darüber liegen (Normale zeigt nach unten) - der Boden eines Lochs ist auch ein Tiefpunkt
    const length = Math.hypot(gx, gy, gz)
    if (!(length > 0) || gz / length > -0.3) continue
    const x = sx / group.length, y = sy / group.length
    // Steht knapp darunter schon etwas (z. B. eine Stützsäule mit Abstand für den Slicer), hängt die Stelle nicht frei
    if (z - surfaceBelow(tris, x, y, z) <= SUPPORT_GAP) continue
    let r = 0
    for (const p of group) r = Math.max(r, Math.hypot(points[p * 3] - x, points[p * 3 + 1] - y))
    islands.push({ x, y, z, r })
  }
  islands.sort((a, b) => a.z - b.z)
  return islands.slice(0, max)
}

/** So nah darf eine Stütze unter einer Stelle enden, ohne dass die Stelle als freihängend gilt (eine Schicht + Luft). */
export const SUPPORT_GAP = 0.6

/** Höhe der nächsten nach oben zeigenden Fläche senkrecht unter (x, y, z), oder -Infinity. */
export function surfaceBelow(tris, x, y, z) {
  let best = -Infinity
  for (let o = 0; o + 8 < tris.length; o += 9) {
    const ax = tris[o], ay = tris[o + 1], bx = tris[o + 3], by = tris[o + 4], cx = tris[o + 6], cy = tris[o + 7]
    const det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if (Math.abs(det) < 1e-12) continue
    const l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / det
    const l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / det
    const l3 = 1 - l1 - l2
    if (l1 < -1e-9 || l2 < -1e-9 || l3 < -1e-9) continue
    // Nach oben zeigend: Umlauf gegen den Uhrzeigersinn von oben gesehen
    if (det <= 0) continue
    const h = l1 * tris[o + 2] + l2 * tris[o + 5] + l3 * tris[o + 8]
    if (h < z - 1e-4 && h > best) best = h
  }
  return best
}

/**
 * Richtungen, in die das Modell nach unten zeigen könnte: die sechs Achsen und die Normalen der größten ebenen Flächen.
 * [tris] wie oben; Ergebnis: Einheitsvektoren [[x, y, z], ...] ohne Doppelte (unter 2°).
 */
export function orientationCandidates(tris, maxFaces = 12) {
  const buckets = new Map()
  for (let o = 0; o + 8 < tris.length; o += 9) {
    const ux = tris[o + 3] - tris[o], uy = tris[o + 4] - tris[o + 1], uz = tris[o + 5] - tris[o + 2]
    const vx = tris[o + 6] - tris[o], vy = tris[o + 7] - tris[o + 1], vz = tris[o + 8] - tris[o + 2]
    const cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx
    const length = Math.hypot(cx, cy, cz)
    if (!(length > 0)) continue
    const n = [cx / length, cy / length, cz / length]
    const offset = n[0] * tris[o] + n[1] * tris[o + 1] + n[2] * tris[o + 2]
    // Gleiche Ebene: gleiche Normale (auf 0,01) und gleicher Abstand vom Ursprung (auf 0,05 mm)
    const key = `${Math.round(n[0] * 100)},${Math.round(n[1] * 100)},${Math.round(n[2] * 100)},${Math.round(offset * 20)}`
    const bucket = buckets.get(key)
    if (bucket) bucket.area += length / 2
    else buckets.set(key, { n, area: length / 2 })
  }
  const faces = [...buckets.values()].sort((a, b) => b.area - a.area).slice(0, maxFaces).map((b) => b.n)
  const axes = [[0, 0, -1], [0, 0, 1], [1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0]]
  const result = []
  for (const d of [...axes, ...faces]) {
    if (result.some((r) => r[0] * d[0] + r[1] * d[1] + r[2] * d[2] > Math.cos(2 * Math.PI / 180))) continue
    result.push(d)
  }
  return result
}

/**
 * Lage mit [down] als Richtung nach unten bewerten: Überhänge (Flächen steiler als [angle]° nach unten, nicht auf dem
 * Bett), Auflage auf dem Bett und Höhe. Ergebnis: { down, overhang, contact, height }.
 */
export function scoreOrientation(tris, down, angle = 45) {
  const [dx, dy, dz] = down
  const sin = Math.sin(angle * Math.PI / 180)
  let minH = Infinity, maxH = -Infinity
  for (let i = 0; i < tris.length; i += 3) {
    const h = -(tris[i] * dx + tris[i + 1] * dy + tris[i + 2] * dz)
    if (h < minH) minH = h
    if (h > maxH) maxH = h
  }
  const eps = Math.max((maxH - minH) * 1e-6, 1e-6)
  let overhang = 0, contact = 0
  for (let o = 0; o + 8 < tris.length; o += 9) {
    const ux = tris[o + 3] - tris[o], uy = tris[o + 4] - tris[o + 1], uz = tris[o + 5] - tris[o + 2]
    const vx = tris[o + 6] - tris[o], vy = tris[o + 7] - tris[o + 1], vz = tris[o + 8] - tris[o + 2]
    const cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx
    const length = Math.hypot(cx, cy, cz)
    if (!(length > 0)) continue
    const facing = (cx * dx + cy * dy + cz * dz) / length
    let top = -Infinity
    for (let k = 0; k < 3; k++) top = Math.max(top, -(tris[o + k * 3] * dx + tris[o + k * 3 + 1] * dy + tris[o + k * 3 + 2] * dz))
    const onBed = top - minH < eps
    if (onBed && facing > 0.99) contact += length / 2
    else if (!onBed && facing > sin) overhang += length / 2
  }
  return { down, overhang, contact, height: maxH - minH }
}

/**
 * Die besten Lagen: wenig Überhang (auf 2 % der Oberfläche gerundet), dann viel Auflage, dann wenig Höhe.
 * Ergebnis: bis zu [count] Bewertungen aus [scoreOrientation], die beste zuerst.
 */
export function bestOrientations(tris, angle = 45, count = 3) {
  let total = 0
  for (let o = 0; o + 8 < tris.length; o += 9) {
    const ux = tris[o + 3] - tris[o], uy = tris[o + 4] - tris[o + 1], uz = tris[o + 5] - tris[o + 2]
    const vx = tris[o + 6] - tris[o], vy = tris[o + 7] - tris[o + 1], vz = tris[o + 8] - tris[o + 2]
    total += Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx) / 2
  }
  const bin = Math.max(total * 0.02, 1e-9)
  const scores = orientationCandidates(tris).map((d) => scoreOrientation(tris, d, angle))
  scores.sort((a, b) => Math.round(a.overhang / bin) - Math.round(b.overhang / bin) || b.contact - a.contact || a.height - b.height)
  // Gleichwertige Lagen (z. B. auf der einen oder der gegenüberliegenden Seite eines symmetrischen Teils) nur einmal
  const same = (a, b) => Math.abs(a.overhang - b.overhang) <= bin / 2 && Math.abs(a.contact - b.contact) <= Math.max(a.contact, b.contact) * 0.01 + 1e-9 &&
    Math.abs(a.height - b.height) <= Math.max(a.height, b.height) * 0.01 + 1e-9
  const result = []
  for (const score of scores) {
    if (result.some((r) => same(r, score))) continue
    result.push(score)
    if (result.length === count) break
  }
  return result
}

/**
 * Schnitte, damit jedes Teil aufs Bett passt: je Achse, auf der das Modell länger ist als das Bett (abzüglich [margin]),
 * gleichmäßig verteilte Schnitte. [min], [max]: Umriss (Koordinaten der Datei), [bed]: [Breite, Tiefe, Höhe].
 * Ergebnis: [{ axis, value }].
 */
export function proposeCuts(min, max, bed, margin = 5) {
  const cuts = []
  for (let axis = 0; axis < 3; axis++) {
    const length = max[axis] - min[axis]
    const room = bed[axis] - margin
    if (!(room > 0) || length <= bed[axis]) continue
    const pieces = Math.ceil(length / room)
    for (let i = 1; i < pieces; i++) cuts.push({ axis, value: Math.round((min[axis] + length * i / pieces) * 100) / 100 })
  }
  return cuts
}
