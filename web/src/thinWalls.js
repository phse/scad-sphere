// Wanddicke für die Druckprüfung: Von der Mitte jedes Dreiecks geht ein Strahl gegen die Normale ins Material; trifft er
// innerhalb von [minWall] mm die Rückseite einer anderen Fläche (deren Normale in Strahlrichtung zeigt), ist die Wand dort
// dünner als eine Düsenbreite o. Ä. Damit das auch bei großen Modellen schnell geht, liegen die Dreiecke in einer BVH;
// die Strahlen sind kurz, die Suche bleibt also nah am Startpunkt.

const LEAF = 6

/**
 * [tris]: Float32Array/Array mit neun Zahlen je Dreieck (Szenen-Koordinaten), [normals]: drei Zahlen je Dreieck (Einheit,
 * nach außen). Ergebnis: { thin: [Dreiecksnummern], area } - area in mm².
 */
export function findThinWalls(tris, normals, minWall) {
  const count = Math.floor(tris.length / 9)
  const result = { thin: [], area: 0 }
  if (!count || !(minWall > 0)) return result
  const bvh = buildBvh(tris, count)
  const origin = [0, 0, 0]
  const dir = [0, 0, 0]
  for (let t = 0; t < count; t++) {
    const o = t * 9
    const nx = normals[t * 3], ny = normals[t * 3 + 1], nz = normals[t * 3 + 2]
    if (nx === 0 && ny === 0 && nz === 0) continue
    const area = triangleArea(tris, o)
    if (area <= 0) continue
    // Etwas unter der Oberfläche starten, damit der Strahl die eigene Fläche und ihre Nachbarn nicht trifft
    const eps = Math.max(minWall * 1e-3, 1e-5)
    origin[0] = (tris[o] + tris[o + 3] + tris[o + 6]) / 3 - nx * eps
    origin[1] = (tris[o + 1] + tris[o + 4] + tris[o + 7]) / 3 - ny * eps
    origin[2] = (tris[o + 2] + tris[o + 5] + tris[o + 8]) / 3 - nz * eps
    dir[0] = -nx; dir[1] = -ny; dir[2] = -nz
    const hit = nearestHit(bvh, tris, normals, origin, dir, minWall - eps, t)
    if (hit < Infinity) {
      result.thin.push(t)
      result.area += area
    }
  }
  return result
}

function triangleArea(p, o) {
  const ux = p[o + 3] - p[o], uy = p[o + 4] - p[o + 1], uz = p[o + 5] - p[o + 2]
  const vx = p[o + 6] - p[o], vy = p[o + 7] - p[o + 1], vz = p[o + 8] - p[o + 2]
  const cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx
  return Math.sqrt(cx * cx + cy * cy + cz * cz) / 2
}

/** BVH als flache Arrays: je Knoten Box (6 Zahlen), dann links/rechts oder Bereich in [order]. */
function buildBvh(tris, count) {
  const order = new Uint32Array(count)
  const centers = new Float32Array(count * 3)
  for (let t = 0; t < count; t++) {
    order[t] = t
    const o = t * 9
    centers[t * 3] = (tris[o] + tris[o + 3] + tris[o + 6]) / 3
    centers[t * 3 + 1] = (tris[o + 1] + tris[o + 4] + tris[o + 7]) / 3
    centers[t * 3 + 2] = (tris[o + 2] + tris[o + 5] + tris[o + 8]) / 3
  }
  const boxes = []
  const nodes = []   // [left, right, start, end]; left < 0 = Blatt
  const build = (start, end) => {
    const index = nodes.length
    nodes.push(null)
    let minX = Infinity, minY = Infinity, minZ = Infinity, maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity
    let cminX = Infinity, cminY = Infinity, cminZ = Infinity, cmaxX = -Infinity, cmaxY = -Infinity, cmaxZ = -Infinity
    for (let i = start; i < end; i++) {
      const t = order[i]
      const o = t * 9
      for (let k = 0; k < 9; k += 3) {
        const x = tris[o + k], y = tris[o + k + 1], z = tris[o + k + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
      }
      const cx = centers[t * 3], cy = centers[t * 3 + 1], cz = centers[t * 3 + 2]
      if (cx < cminX) cminX = cx; if (cx > cmaxX) cmaxX = cx
      if (cy < cminY) cminY = cy; if (cy > cmaxY) cmaxY = cy
      if (cz < cminZ) cminZ = cz; if (cz > cmaxZ) cmaxZ = cz
    }
    boxes[index] = [minX, minY, minZ, maxX, maxY, maxZ]
    const ex = cmaxX - cminX, ey = cmaxY - cminY, ez = cmaxZ - cminZ
    if (end - start <= LEAF || Math.max(ex, ey, ez) === 0) {
      nodes[index] = [-1, -1, start, end]
      return index
    }
    const axis = ex >= ey && ex >= ez ? 0 : ey >= ez ? 1 : 2
    const split = axis === 0 ? cminX + ex / 2 : axis === 1 ? cminY + ey / 2 : cminZ + ez / 2
    // Aufteilen nach der Mitte der Mittelpunkte
    let i = start, j = end - 1
    while (i <= j) {
      if (centers[order[i] * 3 + axis] < split) i++
      else { const tmp = order[i]; order[i] = order[j]; order[j] = tmp; j-- }
    }
    let mid = i
    if (mid === start || mid === end) mid = (start + end) >> 1
    const left = build(start, mid)
    const right = build(mid, end)
    nodes[index] = [left, right, start, end]
    return index
  }
  build(0, count)
  return { boxes, nodes, order }
}

function boxHit(box, o, inv, maxT) {
  let tmin = 0, tmax = maxT
  for (let a = 0; a < 3; a++) {
    let t1 = (box[a] - o[a]) * inv[a]
    let t2 = (box[a + 3] - o[a]) * inv[a]
    if (t1 > t2) { const s = t1; t1 = t2; t2 = s }
    if (t1 > tmin) tmin = t1
    if (t2 < tmax) tmax = t2
    if (tmin > tmax) return false
  }
  return true
}

/** Abstand zum nächsten Treffer einer Rückseite (Normale in Strahlrichtung) bis [maxT], sonst Infinity. */
function nearestHit(bvh, tris, normals, o, d, maxT, skip) {
  const inv = [1 / d[0], 1 / d[1], 1 / d[2]]
  let best = Infinity
  const stack = [0]
  while (stack.length) {
    const index = stack.pop()
    if (!boxHit(bvh.boxes[index], o, inv, Math.min(maxT, best))) continue
    const node = bvh.nodes[index]
    if (node[0] >= 0) {
      stack.push(node[0], node[1])
      continue
    }
    for (let i = node[2]; i < node[3]; i++) {
      const t = bvh.order[i]
      if (t === skip) continue
      // Nur Rückseiten: die Wand gegenüber, nicht Flächen, die nach innen zeigen
      if (normals[t * 3] * d[0] + normals[t * 3 + 1] * d[1] + normals[t * 3 + 2] * d[2] <= 0.05) continue
      const distance = rayTriangle(tris, t * 9, o, d)
      if (distance > 0 && distance < best && distance <= maxT) best = distance
    }
  }
  return best
}

/** Möller-Trumbore, beidseitig; Abstand oder -1. */
function rayTriangle(p, k, o, d) {
  const e1x = p[k + 3] - p[k], e1y = p[k + 4] - p[k + 1], e1z = p[k + 5] - p[k + 2]
  const e2x = p[k + 6] - p[k], e2y = p[k + 7] - p[k + 1], e2z = p[k + 8] - p[k + 2]
  const px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x
  const det = e1x * px + e1y * py + e1z * pz
  if (Math.abs(det) < 1e-12) return -1
  const invDet = 1 / det
  const tx = o[0] - p[k], ty = o[1] - p[k + 1], tz = o[2] - p[k + 2]
  const u = (tx * px + ty * py + tz * pz) * invDet
  if (u < -1e-7 || u > 1 + 1e-7) return -1
  const qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x
  const v = (d[0] * qx + d[1] * qy + d[2] * qz) * invDet
  if (v < -1e-7 || u + v > 1 + 1e-7) return -1
  return (e2x * qx + e2y * qy + e2z * qz) * invDet
}
