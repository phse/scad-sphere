package app.heckit.ide.scadsphere.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Druckprüfung ohne Viewer (MCP-Werkzeuge): dieselben Rechnungen wie im Viewer (web/src/viewer.js, printTools.js,
 * thinWalls.js, splitPins.js) auf den Dreiecken einer STL. Dreiecke als flaches Array, neun Zahlen je Dreieck, Z oben.
 */
object PrintAnalysis {
    /** Dreiecke einer STL (binär oder ASCII). */
    fun trianglesOfStl(bytes: ByteArray): DoubleArray {
        if (bytes.size >= 84) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt(80).toLong() and 0xffffffffL
            if (84 + count * 50 == bytes.size.toLong()) {
                val result = DoubleArray((count * 9).toInt())
                for (i in 0 until count.toInt()) for (k in 0 until 9) result[i * 9 + k] = buffer.getFloat(84 + i * 50 + 12 + k * 4).toDouble()
                return result
            }
        }
        val values = ArrayList<Double>()
        for (line in bytes.toString(Charsets.UTF_8).lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("vertex")) continue
            trimmed.split(Regex("\\s+")).drop(1).take(3).forEach { values += it.toDouble() }
        }
        return values.toDoubleArray()
    }

    class Island(val x: Double, val y: Double, val z: Double, val r: Double)

    class Report(
        val volume: Double,
        val area: Double,
        val overhangArea: Double,
        val overhangLowest: DoubleArray?,
        val contactArea: Double,
        val islands: List<Island>,
        val openEdges: Int,
        val nonManifoldEdges: Int,
        val thinArea: Double,
        /** Mittelpunkte von Stellen mit zu dünner Wand (höchstens einige, nach Z sortiert) */
        val thinSpots: List<DoubleArray>,
        val min: DoubleArray,
        val max: DoubleArray,
    )

    fun check(tris: DoubleArray, angle: Double = 45.0, minWall: Double = 0.8): Report {
        val count = tris.size / 9
        val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        for (i in tris.indices) { val a = i % 3; if (tris[i] < min[a]) min[a] = tris[i]; if (tris[i] > max[a]) max[a] = tris[i] }
        val size = hypot(hypot(max[0] - min[0], max[1] - min[1]), max[2] - min[2])
        val flatEps = max(size * 1e-6, 1e-6)
        val sinAngle = sin(Math.toRadians(angle))
        val normals = DoubleArray(count * 3)
        var volume = 0.0
        var area = 0.0
        var overhang = 0.0
        var contact = 0.0
        var lowest: DoubleArray? = null
        for (t in 0 until count) {
            val o = t * 9
            val ux = tris[o + 3] - tris[o]; val uy = tris[o + 4] - tris[o + 1]; val uz = tris[o + 5] - tris[o + 2]
            val vx = tris[o + 6] - tris[o]; val vy = tris[o + 7] - tris[o + 1]; val vz = tris[o + 8] - tris[o + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            volume += (tris[o] * (tris[o + 4] * tris[o + 8] - tris[o + 5] * tris[o + 7]) +
                tris[o + 1] * (tris[o + 5] * tris[o + 6] - tris[o + 3] * tris[o + 8]) +
                tris[o + 2] * (tris[o + 3] * tris[o + 7] - tris[o + 4] * tris[o + 6])) / 6
            if (length <= 0) continue
            normals[t * 3] = nx / length; normals[t * 3 + 1] = ny / length; normals[t * 3 + 2] = nz / length
            val a = length / 2
            area += a
            val top = maxOf(tris[o + 2], tris[o + 5], tris[o + 8])
            val onBed = top - min[2] < flatEps
            if (onBed && nz / length < -0.99) contact += a
            else if (!onBed && -nz / length > sinAngle) {
                overhang += a
                val low = minOf(tris[o + 2], tris[o + 5], tris[o + 8])
                if (lowest == null || low < lowest[2]) lowest = doubleArrayOf((tris[o] + tris[o + 3] + tris[o + 6]) / 3, (tris[o + 1] + tris[o + 4] + tris[o + 7]) / 3, low)
            }
        }
        val (open, nonManifold) = edgeCounts(tris, size)
        val thin = if (minWall > 0) thinWalls(tris, normals, minWall) else emptyList()
        var thinArea = 0.0
        for (t in thin) thinArea += triangleArea(tris, t * 9)
        val spots = thin.map { t -> val o = t * 9; doubleArrayOf((tris[o] + tris[o + 3] + tris[o + 6]) / 3, (tris[o + 1] + tris[o + 4] + tris[o + 7]) / 3, (tris[o + 2] + tris[o + 5] + tris[o + 8]) / 3) }
            .let { clusterSpots(it, max(minWall * 4, 2.0)) }
        return Report(abs(volume), area, overhang, lowest, contact, islands(tris, min[2], size), open, nonManifold, thinArea, spots, min, max)
    }

    /** Gleiche Punkte zusammenfassen: Kennung je Ecke und die Lage je Kennung. */
    private fun weld(tris: DoubleArray, size: Double): Pair<IntArray, DoubleArray> {
        val quantum = max(size * 1e-7, 1e-7)
        val ids = HashMap<Triple<Long, Long, Long>, Int>()
        val points = ArrayList<Double>()
        val corner = IntArray(tris.size / 3)
        for (i in corner.indices) {
            val key = Triple(Math.round(tris[i * 3] / quantum), Math.round(tris[i * 3 + 1] / quantum), Math.round(tris[i * 3 + 2] / quantum))
            corner[i] = ids.getOrPut(key) { points.add(tris[i * 3]); points.add(tris[i * 3 + 1]); points.add(tris[i * 3 + 2]); points.size / 3 - 1 }
        }
        return corner to points.toDoubleArray()
    }

    private fun edgeCounts(tris: DoubleArray, size: Double): Pair<Int, Int> {
        val (corner, _) = weld(tris, size)
        val uses = HashMap<Long, Int>()
        for (t in 0 until corner.size / 3) {
            for (k in 0 until 3) {
                val a = corner[t * 3 + k]; val b = corner[t * 3 + (k + 1) % 3]
                if (a == b) continue
                val key = if (a < b) a.toLong() * 67108864 + b else b.toLong() * 67108864 + a
                uses.merge(key, 1, Int::plus)
            }
        }
        return uses.values.count { it == 1 } to uses.values.count { it > 2 }
    }

    /** Wie `findIslands` in printTools.js: Tiefpunkte der Oberfläche, an denen das Material nach unten zeigt, nicht auf dem Bett. */
    fun islands(tris: DoubleArray, minZ: Double, size: Double, limit: Int = 50): List<Island> {
        if (tris.isEmpty()) return emptyList()
        val (corner, points) = weld(tris, size)
        val count = points.size / 3
        val eps = max(size * 1e-6, 1e-4)
        val neighbors = Array(count) { ArrayList<Int>(6) }
        val nx = DoubleArray(count); val ny = DoubleArray(count); val nz = DoubleArray(count)
        for (t in 0 until corner.size / 3) {
            val a = corner[t * 3]; val b = corner[t * 3 + 1]; val c = corner[t * 3 + 2]
            if (a == b || b == c || a == c) continue
            val ux = points[b * 3] - points[a * 3]; val uy = points[b * 3 + 1] - points[a * 3 + 1]; val uz = points[b * 3 + 2] - points[a * 3 + 2]
            val vx = points[c * 3] - points[a * 3]; val vy = points[c * 3 + 1] - points[a * 3 + 1]; val vz = points[c * 3 + 2] - points[a * 3 + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            for (p in intArrayOf(a, b, c)) { nx[p] += cx; ny[p] += cy; nz[p] += cz }
            neighbors[a].add(b); neighbors[a].add(c)
            neighbors[b].add(a); neighbors[b].add(c)
            neighbors[c].add(a); neighbors[c].add(b)
        }
        val parent = IntArray(count) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (p in 0 until count) for (q in neighbors[p]) {
            if (abs(points[p * 3 + 2] - points[q * 3 + 2]) < eps) { val rp = find(p); val rq = find(q); if (rp != rq) parent[rp] = rq }
        }
        val groups = HashMap<Int, MutableList<Int>>()
        for (p in 0 until count) groups.getOrPut(find(p)) { ArrayList() }.add(p)
        val result = ArrayList<Island>()
        for (group in groups.values) {
            val z = points[group[0] * 3 + 2]
            if (z - minZ < 0.1) continue
            val members = group.toHashSet()
            var lowestHere = true
            var gx = 0.0; var gy = 0.0; var gz = 0.0; var sx = 0.0; var sy = 0.0
            loop@ for (p in group) {
                for (q in neighbors[p]) if (q !in members && points[q * 3 + 2] < z + eps) { lowestHere = false; break@loop }
                gx += nx[p]; gy += ny[p]; gz += nz[p]; sx += points[p * 3]; sy += points[p * 3 + 1]
            }
            if (!lowestHere) continue
            val length = sqrt(gx * gx + gy * gy + gz * gz)
            if (length <= 0 || gz / length > -0.3) continue
            val x = sx / group.size; val y = sy / group.size
            // Steht knapp darunter schon etwas (z. B. eine Stützsäule mit Abstand für den Slicer), hängt die Stelle nicht frei
            if (z - surfaceBelow(tris, x, y, z) <= SUPPORT_GAP) continue
            val r = group.maxOf { hypot(points[it * 3] - x, points[it * 3 + 1] - y) }
            result += Island(x, y, z, r)
        }
        return result.sortedBy { it.z }.take(limit)
    }

    /** So nah darf eine Stütze unter einer Stelle enden, ohne dass die Stelle als freihängend gilt (wie im Viewer). */
    const val SUPPORT_GAP = 0.6

    /** Höhe der nächsten nach oben zeigenden Fläche senkrecht unter (x, y, z), oder -Infinity. */
    fun surfaceBelow(tris: DoubleArray, x: Double, y: Double, z: Double): Double {
        var best = Double.NEGATIVE_INFINITY
        var o = 0
        while (o + 8 < tris.size) {
            val ax = tris[o]; val ay = tris[o + 1]; val bx = tris[o + 3]; val by = tris[o + 4]; val cx = tris[o + 6]; val cy = tris[o + 7]
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (det > 1e-12) {
                val l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / det
                val l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / det
                val l3 = 1 - l1 - l2
                if (l1 >= -1e-9 && l2 >= -1e-9 && l3 >= -1e-9) {
                    val h = l1 * tris[o + 2] + l2 * tris[o + 5] + l3 * tris[o + 8]
                    if (h < z - 1e-4 && h > best) best = h
                }
            }
            o += 9
        }
        return best
    }

    private fun triangleArea(p: DoubleArray, o: Int): Double {
        val ux = p[o + 3] - p[o]; val uy = p[o + 4] - p[o + 1]; val uz = p[o + 5] - p[o + 2]
        val vx = p[o + 6] - p[o]; val vy = p[o + 7] - p[o + 1]; val vz = p[o + 8] - p[o + 2]
        return sqrt((uy * vz - uz * vy).pow(2) + (uz * vx - ux * vz).pow(2) + (ux * vy - uy * vx).pow(2)) / 2
    }

    /** Nahe beieinander liegende Stellen zu einer zusammenfassen (Mittelpunkt der ersten), höchstens [limit]. */
    private fun clusterSpots(spots: List<DoubleArray>, radius: Double, limit: Int = 10): List<DoubleArray> {
        val result = ArrayList<DoubleArray>()
        for (spot in spots.sortedBy { it[2] }) {
            if (result.none { hypot(hypot(it[0] - spot[0], it[1] - spot[1]), it[2] - spot[2]) < radius }) result += spot
            if (result.size >= limit) break
        }
        return result
    }

    // --- Wanddicke wie thinWalls.js: Strahl von der Mitte jedes Dreiecks gegen die Normale, Treffer einer Rückseite ---

    private class Bvh(val boxes: DoubleArray, val left: IntArray, val right: IntArray, val start: IntArray, val end: IntArray, val order: IntArray)

    private fun buildBvh(tris: DoubleArray, count: Int): Bvh {
        val order = IntArray(count) { it }
        val centers = DoubleArray(count * 3)
        for (t in 0 until count) for (a in 0 until 3) centers[t * 3 + a] = (tris[t * 9 + a] + tris[t * 9 + 3 + a] + tris[t * 9 + 6 + a]) / 3
        val boxes = ArrayList<Double>(); val left = ArrayList<Int>(); val right = ArrayList<Int>(); val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
        fun build(start: Int, end: Int): Int {
            val index = left.size
            left += -1; right += -1; starts += start; ends += end
            repeat(6) { boxes += 0.0 }
            val lo = DoubleArray(3) { Double.POSITIVE_INFINITY }; val hi = DoubleArray(3) { Double.NEGATIVE_INFINITY }
            val clo = DoubleArray(3) { Double.POSITIVE_INFINITY }; val chi = DoubleArray(3) { Double.NEGATIVE_INFINITY }
            for (i in start until end) {
                val t = order[i]
                for (k in 0 until 9) { val a = k % 3; val v = tris[t * 9 + k]; if (v < lo[a]) lo[a] = v; if (v > hi[a]) hi[a] = v }
                for (a in 0 until 3) { val c = centers[t * 3 + a]; if (c < clo[a]) clo[a] = c; if (c > chi[a]) chi[a] = c }
            }
            for (a in 0 until 3) { boxes[index * 6 + a] = lo[a]; boxes[index * 6 + 3 + a] = hi[a] }
            val extent = DoubleArray(3) { chi[it] - clo[it] }
            if (end - start <= 6 || extent.max() == 0.0) return index
            val axis = if (extent[0] >= extent[1] && extent[0] >= extent[2]) 0 else if (extent[1] >= extent[2]) 1 else 2
            val split = clo[axis] + extent[axis] / 2
            var i = start; var j = end - 1
            while (i <= j) {
                if (centers[order[i] * 3 + axis] < split) i++ else { val tmp = order[i]; order[i] = order[j]; order[j] = tmp; j-- }
            }
            var mid = i
            if (mid == start || mid == end) mid = (start + end) / 2
            val l = build(start, mid)
            val r = build(mid, end)
            left[index] = l; right[index] = r
            return index
        }
        build(0, count)
        return Bvh(boxes.toDoubleArray(), left.toIntArray(), right.toIntArray(), starts.toIntArray(), ends.toIntArray(), order)
    }

    private fun thinWalls(tris: DoubleArray, normals: DoubleArray, minWall: Double): List<Int> {
        val count = tris.size / 9
        if (count == 0) return emptyList()
        val bvh = buildBvh(tris, count)
        val eps = max(minWall * 1e-3, 1e-5)
        val origin = DoubleArray(3); val dir = DoubleArray(3)
        val result = ArrayList<Int>()
        for (t in 0 until count) {
            val nx = normals[t * 3]; val ny = normals[t * 3 + 1]; val nz = normals[t * 3 + 2]
            if (nx == 0.0 && ny == 0.0 && nz == 0.0) continue
            val o = t * 9
            for (a in 0 until 3) origin[a] = (tris[o + a] + tris[o + 3 + a] + tris[o + 6 + a]) / 3 - normals[t * 3 + a] * eps
            dir[0] = -nx; dir[1] = -ny; dir[2] = -nz
            if (nearestHit(bvh, tris, normals, origin, dir, minWall - eps, t) < Double.POSITIVE_INFINITY) result += t
        }
        return result
    }

    private fun nearestHit(bvh: Bvh, tris: DoubleArray, normals: DoubleArray, o: DoubleArray, d: DoubleArray, maxT: Double, skip: Int): Double {
        val inv = DoubleArray(3) { 1 / d[it] }
        var best = Double.POSITIVE_INFINITY
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        while (stack.isNotEmpty()) {
            val index = stack.removeLast()
            if (!boxHit(bvh.boxes, index, o, inv, min(maxT, best))) continue
            if (bvh.left[index] >= 0) { stack.addLast(bvh.left[index]); stack.addLast(bvh.right[index]); continue }
            for (i in bvh.start[index] until bvh.end[index]) {
                val t = bvh.order[i]
                if (t == skip) continue
                if (normals[t * 3] * d[0] + normals[t * 3 + 1] * d[1] + normals[t * 3 + 2] * d[2] <= 0.05) continue
                val distance = rayTriangle(tris, t * 9, o, d)
                if (distance > 0 && distance < best && distance <= maxT) best = distance
            }
        }
        return best
    }

    private fun boxHit(boxes: DoubleArray, index: Int, o: DoubleArray, inv: DoubleArray, maxT: Double): Boolean {
        var tmin = 0.0; var tmax = maxT
        for (a in 0 until 3) {
            var t1 = (boxes[index * 6 + a] - o[a]) * inv[a]
            var t2 = (boxes[index * 6 + 3 + a] - o[a]) * inv[a]
            if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
            if (t1 > tmin) tmin = t1
            if (t2 < tmax) tmax = t2
            if (tmin > tmax) return false
        }
        return true
    }

    private fun rayTriangle(p: DoubleArray, k: Int, o: DoubleArray, d: DoubleArray): Double {
        val e1x = p[k + 3] - p[k]; val e1y = p[k + 4] - p[k + 1]; val e1z = p[k + 5] - p[k + 2]
        val e2x = p[k + 6] - p[k]; val e2y = p[k + 7] - p[k + 1]; val e2z = p[k + 8] - p[k + 2]
        val px = d[1] * e2z - d[2] * e2y; val py = d[2] * e2x - d[0] * e2z; val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-12) return -1.0
        val invDet = 1 / det
        val tx = o[0] - p[k]; val ty = o[1] - p[k + 1]; val tz = o[2] - p[k + 2]
        val u = (tx * px + ty * py + tz * pz) * invDet
        if (u < -1e-7 || u > 1 + 1e-7) return -1.0
        val qx = ty * e1z - tz * e1y; val qy = tz * e1x - tx * e1z; val qz = tx * e1y - ty * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) * invDet
        if (v < -1e-7 || u + v > 1 + 1e-7) return -1.0
        return (e2x * qx + e2y * qy + e2z * qz) * invDet
    }

    // --- Schnitt ---

    /**
     * Schnitt quer zur Achse [axis] bei [value]: Strecken in der Ebene als (u1, v1, u2, v2), u und v = die beiden anderen
     * Achsen aufsteigend. Jede Strecke ist so gerichtet, dass das Material links liegt (aus der Normalen des Dreiecks) - die
     * Fläche ergibt sich dann ohne Zusammensetzen der Umrisse aus der Summe der Kreuzprodukte (Löcher zählen negativ).
     */
    class Section(val axis: Int, val value: Double, val segments: DoubleArray) {
        val area: Double get() {
            var sum = 0.0
            for (i in segments.indices step 4) sum += segments[i] * segments[i + 3] - segments[i + 2] * segments[i + 1]
            return abs(sum / 2)
        }
        val perimeter: Double get() {
            var sum = 0.0
            for (i in segments.indices step 4) sum += hypot(segments[i + 2] - segments[i], segments[i + 3] - segments[i + 1])
            return sum
        }
        val isEmpty: Boolean get() = segments.isEmpty()

        /** Umriss in der Ebene: [minU, minV, maxU, maxV]. */
        fun extent(): DoubleArray {
            val e = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
            for (i in segments.indices step 2) {
                e[0] = min(e[0], segments[i]); e[2] = max(e[2], segments[i])
                e[1] = min(e[1], segments[i + 1]); e[3] = max(e[3], segments[i + 1])
            }
            return e
        }

        /** Getrennte Stücke der Schnittfläche (Umrisse ohne Löcher): Anzahl der äußeren Ringe nach Umlaufsinn. */
        fun pieces(): Int {
            val loops = loops()
            // Äußere Ringe haben das Vorzeichen der Gesamtfläche
            var total = 0.0
            val signed = loops.map { loop -> var s = 0.0; for (i in loop.indices step 4) s += loop[i] * loop[i + 3] - loop[i + 2] * loop[i + 1]; s }
            signed.forEach { total += it }
            return signed.count { it * total > 0 && abs(it) > 1e-9 }
        }

        /** Strecken zu geschlossenen Ringen verbinden (über gerundete Endpunkte). */
        private fun loops(): List<DoubleArray> {
            val count = segments.size / 4
            val quantum = 1e-4
            fun key(u: Double, v: Double) = Math.round(u / quantum) * 1_000_003L + Math.round(v / quantum)
            val byStart = HashMap<Long, ArrayDeque<Int>>()
            for (i in 0 until count) byStart.getOrPut(key(segments[i * 4], segments[i * 4 + 1])) { ArrayDeque() }.addLast(i)
            val used = BooleanArray(count)
            val result = ArrayList<DoubleArray>()
            for (first in 0 until count) {
                if (used[first]) continue
                val loop = ArrayList<Double>()
                var current = first
                while (current >= 0 && !used[current]) {
                    used[current] = true
                    for (k in 0 until 4) loop += segments[current * 4 + k]
                    val next = byStart[key(segments[current * 4 + 2], segments[current * 4 + 3])]
                    current = next?.firstOrNull { !used[it] } ?: -1
                }
                result += loop.toDoubleArray()
            }
            return result
        }
    }

    fun section(tris: DoubleArray, axis: Int, value: Double): Section {
        val (ua, va) = (0..2).filter { it != axis }
        val segments = ArrayList<Double>()
        val points = DoubleArray(4)
        for (o in 0 until tris.size - 8 step 9) {
            var found = 0
            for (k in 0 until 3) {
                val p = o + k * 3
                val q = o + ((k + 1) % 3) * 3
                val dp = tris[p + axis] - value
                val dq = tris[q + axis] - value
                if ((dp < 0) != (dq < 0) && found < 2) {
                    val s = dp / (dp - dq)
                    points[found * 2] = tris[p + ua] + (tris[q + ua] - tris[p + ua]) * s
                    points[found * 2 + 1] = tris[p + va] + (tris[q + va] - tris[p + va]) * s
                    found++
                }
            }
            if (found != 2) continue
            // Richtung: Achse × Normale (in u/v) - so liegt das Material links
            val ux = tris[o + 3] - tris[o]; val uy = tris[o + 4] - tris[o + 1]; val uz = tris[o + 5] - tris[o + 2]
            val vx = tris[o + 6] - tris[o]; val vy = tris[o + 7] - tris[o + 1]; val vz = tris[o + 8] - tris[o + 2]
            val n = doubleArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            val e = DoubleArray(3).also { it[axis] = 1.0 }
            val t = doubleArrayOf(e[1] * n[2] - e[2] * n[1], e[2] * n[0] - e[0] * n[2], e[0] * n[1] - e[1] * n[0])
            // (u, v, Achse) ist bei Y linkshändig - die Richtung in u/v dreht sich dann
            val handed = if (axis == 1) -1 else 1
            val along = (points[2] - points[0]) * t[ua] + (points[3] - points[1]) * t[va]
            if (along * handed >= 0) segments.addAll(listOf(points[0], points[1], points[2], points[3]))
            else segments.addAll(listOf(points[2], points[3], points[0], points[1]))
        }
        return Section(axis, value, segments.toDoubleArray())
    }

    /**
     * Bild des Schnitts als PNG nach [target]: Fläche schraffiert (gerade/ungerade Regel je Bildzeile), Umriss, Maßstab und
     * Beschriftung der Achsen. [size]: längere Seite in Pixeln.
     */
    fun drawSection(section: Section, target: File, size: Int, title: String) {
        val (ua, va) = (0..2).filter { it != section.axis }
        val extent = section.extent()
        val width = max(extent[2] - extent[0], 1e-6)
        val height = max(extent[3] - extent[1], 1e-6)
        val margin = 48
        val scale = (size - 2 * margin) / max(width, height)
        val imageWidth = (width * scale).roundToInt() + 2 * margin
        val imageHeight = (height * scale).roundToInt() + 2 * margin + 24
        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, imageWidth, imageHeight)
        fun px(u: Double) = margin + (u - extent[0]) * scale
        fun py(v: Double) = margin + 24 + (extent[3] - v) * scale
        // Füllung: je Bildzeile die Schnittpunkte mit den Strecken, dazwischen abwechselnd innen/außen
        val s = section.segments
        val fill = Color(0xd8dbe2)
        val hatch = Color(0x9ea3ae)
        for (y in margin + 24 until imageHeight - margin) {
            val v = extent[3] - (y + 0.5 - margin - 24) / scale
            val crossings = ArrayList<Double>()
            for (i in s.indices step 4) {
                val v1 = s[i + 1]; val v2 = s[i + 3]
                if ((v1 > v) == (v2 > v)) continue
                crossings += s[i] + (v - v1) / (v2 - v1) * (s[i + 2] - s[i])
            }
            crossings.sort()
            for (k in 0 until crossings.size - 1 step 2) {
                val x1 = px(crossings[k]).roundToInt(); val x2 = px(crossings[k + 1]).roundToInt()
                for (x in x1 until x2) image.setRGB(x, y, if ((x + y) % 8 == 0) hatch.rgb else fill.rgb)
            }
        }
        g.color = Color(0x2b2d30)
        g.stroke = BasicStroke(1.5f)
        for (i in s.indices step 4) {
            g.drawLine(px(s[i]).roundToInt(), py(s[i + 1]).roundToInt(), px(s[i + 2]).roundToInt(), py(s[i + 3]).roundToInt())
        }
        // Maßstab unten links: runde Länge um ein Viertel der Breite
        val raw = width / 4
        val step = 10.0.pow(floor(log10(raw)))
        val bar = listOf(1.0, 2.0, 5.0, 10.0).map { it * step }.last { it <= raw * 1.5 }
        val barY = imageHeight - 18
        g.stroke = BasicStroke(2f)
        g.drawLine(margin, barY, (margin + bar * scale).roundToInt(), barY)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.drawString("${PrintAnalysisFormat.number(bar)} mm", (margin + bar * scale + 6).roundToInt(), barY + 4)
        g.drawString(title, margin, 20)
        // Achsen: u nach rechts, v nach oben, mit den Werten an den Rändern
        g.color = Color(0x6f737a)
        val names = "XYZ"
        g.drawString("${names[ua]} ${PrintAnalysisFormat.number(extent[0])}", margin, imageHeight - margin + 14)
        val right = "${PrintAnalysisFormat.number(extent[2])}"
        g.drawString(right, imageWidth - margin - g.fontMetrics.stringWidth(right), imageHeight - margin + 14)
        g.drawString("${names[va]} ${PrintAnalysisFormat.number(extent[3])}", 4, margin + 30)
        g.drawString(PrintAnalysisFormat.number(extent[1]), 4, imageHeight - margin)
        g.dispose()
        target.parentFile?.mkdirs()
        ImageIO.write(image, "png", target)
    }
}

internal object PrintAnalysisFormat {
    fun number(value: Double): String = app.heckit.ide.scadsphere.customizer.formatNumber(Math.round(value * 100) / 100.0)
}
