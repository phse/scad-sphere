// Druckgerecht teilen: Schnittfläche des Modells an einer achsparallelen Ebene und Stellen für Passstifte darin.
// Die Schnittfläche entsteht aus den Strecken, in denen die Dreiecke die Ebene schneiden (gerade/ungerade-Regel:
// innen ist, wo ein Strahl ungerade viele Strecken kreuzt). Stifte kommen dorthin, wo rundum genug Material ist -
// je zusammenhängendem Stück zwei möglichst weit auseinander (verdrehsicher), bei kleinen Stücken einer.

/**
 * [tris]: neun Zahlen je Dreieck, [axis]: 0/1/2, [value]: Lage der Ebene. Ergebnis: Strecken in der Ebene als
 * [u1, v1, u2, v2, ...] mit (u, v) = die beiden anderen Achsen in aufsteigender Reihenfolge.
 */
export function sectionSegments(tris, axis, value) {
  const [ua, va] = [0, 1, 2].filter((a) => a !== axis)
  const segments = []
  for (let o = 0; o + 8 < tris.length; o += 9) {
    const points = []
    for (let k = 0; k < 3; k++) {
      const p = o + k * 3
      const q = o + ((k + 1) % 3) * 3
      const dp = tris[p + axis] - value
      const dq = tris[q + axis] - value
      // Kante kreuzt die Ebene (Ecken genau auf der Ebene zählen zur oberen Seite, so entsteht keine Strecke doppelt)
      if ((dp < 0) !== (dq < 0)) {
        const s = dp / (dp - dq)
        points.push(tris[p + ua] + (tris[q + ua] - tris[p + ua]) * s, tris[p + va] + (tris[q + va] - tris[p + va]) * s)
      }
    }
    if (points.length === 4) segments.push(...points)
  }
  return segments
}

function inside(segments, u, v) {
  let crossings = 0
  for (let i = 0; i < segments.length; i += 4) {
    const v1 = segments[i + 1], v2 = segments[i + 3]
    if ((v1 > v) === (v2 > v)) continue
    const uCross = segments[i] + (v - v1) / (v2 - v1) * (segments[i + 2] - segments[i])
    if (uCross > u) crossings++
  }
  return crossings % 2 === 1
}

function distanceToSegments(segments, u, v) {
  let best = Infinity
  for (let i = 0; i < segments.length; i += 4) {
    const ax = segments[i], ay = segments[i + 1], bx = segments[i + 2], by = segments[i + 3]
    const dx = bx - ax, dy = by - ay
    const length2 = dx * dx + dy * dy
    const s = length2 > 0 ? Math.max(0, Math.min(1, ((u - ax) * dx + (v - ay) * dy) / length2)) : 0
    const d = Math.hypot(u - ax - s * dx, v - ay - s * dy)
    if (d < best) best = d
  }
  return best
}

/**
 * Stellen für Stifte mit Durchmesser [pinD] (plus [wall] mm Material rundum): Raster über die Schnittfläche, je Stück
 * (verbundene Rasterpunkte mit genug Abstand zum Rand) zwei Punkte möglichst weit auseinander oder einer in der Mitte.
 * Ergebnis: { pins: [[u, v], ...], area } (area: Schnittfläche in mm², grob aus dem Raster).
 */
export function placePins(segments, pinD, wall = 1.6, maxPins = 6) {
  if (!segments.length) return { pins: [], area: 0 }
  let minU = Infinity, minV = Infinity, maxU = -Infinity, maxV = -Infinity
  for (let i = 0; i < segments.length; i += 2) {
    minU = Math.min(minU, segments[i]); maxU = Math.max(maxU, segments[i])
    minV = Math.min(minV, segments[i + 1]); maxV = Math.max(maxV, segments[i + 1])
  }
  const need = pinD / 2 + wall
  const extent = Math.max(maxU - minU, maxV - minV)
  const step = Math.max(extent / 120, Math.min(need / 3, extent / 20), 0.05)
  const columns = Math.ceil((maxU - minU) / step) + 1
  const rows = Math.ceil((maxV - minV) / step) + 1
  const clearance = new Float32Array(columns * rows).fill(-1)
  let insideCount = 0
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < columns; c++) {
      // Um einen halben Schritt versetzt, damit Rasterpunkte nicht genau auf Kanten liegen
      const u = minU + (c + 0.5) * step, v = minV + (r + 0.5) * step
      if (!inside(segments, u, v)) continue
      insideCount++
      clearance[r * columns + c] = distanceToSegments(segments, u, v)
    }
  }
  const area = insideCount * step * step
  // Stücke: verbundene Rasterpunkte mit genug Platz für den Stift
  const seen = new Uint8Array(columns * rows)
  const pieces = []
  for (let start = 0; start < clearance.length; start++) {
    if (seen[start] || clearance[start] < need) continue
    const piece = []
    const stack = [start]
    seen[start] = 1
    while (stack.length) {
      const cell = stack.pop()
      piece.push(cell)
      const r = Math.floor(cell / columns), c = cell % columns
      for (const [dr, dc] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const nr = r + dr, nc = c + dc
        if (nr < 0 || nc < 0 || nr >= rows || nc >= columns) continue
        const next = nr * columns + nc
        if (seen[next] || clearance[next] < need) continue
        seen[next] = 1
        stack.push(next)
      }
    }
    pieces.push(piece)
  }
  pieces.sort((a, b) => b.length - a.length)
  const point = (cell) => [minU + ((cell % columns) + 0.5) * step, minV + (Math.floor(cell / columns) + 0.5) * step]
  const pins = []
  for (const piece of pieces) {
    if (pins.length >= maxPins) break
    // Zwei Stifte, wenn sie mindestens zweieinhalb Durchmesser auseinander liegen können
    let bestPair = null
    let bestDistance = 2.5 * pinD
    const sample = piece.length > 600 ? piece.filter((_, i) => i % Math.ceil(piece.length / 600) === 0) : piece
    for (let i = 0; i < sample.length; i++) {
      const a = point(sample[i])
      for (let j = i + 1; j < sample.length; j++) {
        const b = point(sample[j])
        const d = Math.hypot(a[0] - b[0], a[1] - b[1])
        if (d > bestDistance) { bestDistance = d; bestPair = [a, b] }
      }
    }
    if (bestPair && pins.length + 2 <= maxPins) {
      // Nicht ganz an den Rand des Stücks: je ein Fünftel zur Mitte hin
      const [a, b] = bestPair
      const mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
      const towards = (p) => {
        const q = [p[0] + (mid[0] - p[0]) * 0.2, p[1] + (mid[1] - p[1]) * 0.2]
        return clearanceAt(q) >= need ? q : p
      }
      pins.push(towards(a), towards(b))
    } else {
      // Ein Stift: wo am meisten Material rundum ist, bei Gleichstand (lange Stege) nah an der Mitte des Stücks
      let cu = 0, cv = 0
      for (const cell of piece) { const [u, v] = point(cell); cu += u / piece.length; cv += v / piece.length }
      let best = piece[0]
      let bestScore = -Infinity
      for (const cell of piece) {
        const [u, v] = point(cell)
        const score = Math.floor(clearance[cell] / (step / 2)) * 1e6 - Math.hypot(u - cu, v - cv)
        if (score > bestScore) { bestScore = score; best = cell }
      }
      pins.push(point(best))
    }
  }
  function clearanceAt([u, v]) {
    return inside(segments, u, v) ? distanceToSegments(segments, u, v) : -1
  }
  return { pins: pins.map(([u, v]) => [round(u), round(v)]), area }
}

const round = (value) => Math.round(value * 100) / 100
