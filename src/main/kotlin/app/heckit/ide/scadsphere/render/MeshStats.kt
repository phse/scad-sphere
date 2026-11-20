package app.heckit.ide.scadsphere.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Kennzahlen eines Dreiecksnetzes: Ausdehnung, Anzahl der Dreiecke, Volumen (mm³) und Oberfläche (mm²), dazu die
 * Überhänge beim Druck in +Z ([overhang]) - wie die Druckprüfung im Viewer.
 */
class MeshStats(
    val min: DoubleArray,
    val max: DoubleArray,
    val triangles: Int,
    val volume: Double,
    val area: Double,
    val overhang: Overhang = Overhang.NONE,
) {
    val size: DoubleArray get() = DoubleArray(3) { max[it] - min[it] }

    /**
     * Flächen, die mehr als [OVERHANG_ANGLE]° von der Senkrechten nach unten zeigen und nicht auf dem Bett liegen:
     * Fläche (mm²), davon waagrechte Unterseiten ([flatArea], brauchen Stützen oder Brücken), Z-Bereich und die tiefste Stelle.
     */
    class Overhang(val area: Double, val flatArea: Double, val minZ: Double, val maxZ: Double, val lowest: DoubleArray?) {
        companion object {
            val NONE = Overhang(0.0, 0.0, 0.0, 0.0, null)
        }
    }

    companion object {
        /** Aus einer STL-Datei, binär oder ASCII; null ohne Dreiecke. */
        fun ofStl(bytes: ByteArray): MeshStats? {
            val collector = Collector()
            if (isBinary(bytes)) {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val count = buffer.getInt(80)
                val corners = DoubleArray(9)
                for (i in 0 until count) {
                    val offset = 84 + i * 50 + 12
                    for (k in 0 until 9) corners[k] = buffer.getFloat(offset + k * 4).toDouble()
                    collector.add(corners)
                }
            } else {
                val corners = DoubleArray(9)
                var filled = 0
                for (line in String(bytes, Charsets.US_ASCII).lineSequence()) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("vertex")) continue
                    val parts = trimmed.split(WHITESPACE)
                    if (parts.size < 4) continue
                    for (k in 0 until 3) corners[filled + k] = parts[k + 1].toDoubleOrNull() ?: 0.0
                    filled += 3
                    if (filled == 9) { collector.add(corners); filled = 0 }
                }
            }
            return collector.result()
        }

        /** Binär, wenn die Länge zur Dreieckszahl im Kopf passt (auch binäre Dateien dürfen mit „solid“ anfangen). */
        private fun isBinary(bytes: ByteArray): Boolean {
            if (bytes.size < 84) return false
            val count = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(80).toLong() and 0xffffffffL
            return 84 + count * 50 == bytes.size.toLong()
        }

        private val WHITESPACE = Regex("\\s+")

        /** Grenzwinkel der Überhänge in Grad zur Senkrechten (wie die Voreinstellung der Druckprüfung im Viewer). */
        const val OVERHANG_ANGLE = 45.0
        private val OVERHANG_SIN = kotlin.math.sin(Math.toRadians(OVERHANG_ANGLE))
    }

    private class Collector {
        val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        var triangles = 0
        var volume = 0.0
        var area = 0.0

        // Nach unten zeigende Dreiecke: Fläche, Z der Normalen (Einheit), tiefstes und höchstes Z, Mittelpunkt
        val downward = ArrayList<DoubleArray>()

        /** Ein Dreieck: neun Zahlen, drei Ecken je x, y, z. */
        fun add(c: DoubleArray) {
            triangles++
            for (i in 0 until 9) {
                val axis = i % 3
                if (c[i] < min[axis]) min[axis] = c[i]
                if (c[i] > max[axis]) max[axis] = c[i]
            }
            // Kreuzprodukt der Ecken 2 und 3: Spatprodukt für das Volumen (Tetraeder zum Ursprung)
            val cx = c[4] * c[8] - c[5] * c[7]
            val cy = c[5] * c[6] - c[3] * c[8]
            val cz = c[3] * c[7] - c[4] * c[6]
            volume += (c[0] * cx + c[1] * cy + c[2] * cz) / 6.0
            val ux = c[3] - c[0]; val uy = c[4] - c[1]; val uz = c[5] - c[2]
            val vx = c[6] - c[0]; val vy = c[7] - c[1]; val vz = c[8] - c[2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            area += length / 2.0
            if (length > 0 && nz / length < -OVERHANG_SIN) {
                downward += doubleArrayOf(
                    length / 2.0, nz / length, minOf(c[2], c[5], c[8]), maxOf(c[2], c[5], c[8]),
                    (c[0] + c[3] + c[6]) / 3, (c[1] + c[4] + c[7]) / 3,
                )
            }
        }

        fun result(): MeshStats? = if (triangles == 0) null else MeshStats(min, max, triangles, abs(volume), area, overhang())

        /** Überhänge: nach unten zeigende Dreiecke, die nicht auf dem Bett (tiefstes Z des Modells) liegen. */
        private fun overhang(): Overhang {
            val extent = sqrt((0 until 3).sumOf { (max[it] - min[it]).let { d -> d * d } })
            val eps = maxOf(extent * 1e-6, 1e-6)
            var total = 0.0
            var flat = 0.0
            var low = Double.POSITIVE_INFINITY
            var high = Double.NEGATIVE_INFINITY
            var lowest: DoubleArray? = null
            for (t in downward) {
                if (t[3] - min[2] < eps) continue
                total += t[0]
                if (t[1] < -0.99) flat += t[0]
                if (t[2] < low) {
                    low = t[2]
                    lowest = doubleArrayOf(t[4], t[5], t[2])
                }
                high = maxOf(high, t[3])
            }
            return if (total == 0.0) Overhang.NONE else Overhang(total, flat, low, high, lowest)
        }
    }
}
