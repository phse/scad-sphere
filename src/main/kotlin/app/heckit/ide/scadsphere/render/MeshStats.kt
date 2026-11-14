package app.heckit.ide.scadsphere.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** Kennzahlen eines Dreiecksnetzes: Ausdehnung, Anzahl der Dreiecke, Volumen (mm³) und Oberfläche (mm²). */
class MeshStats(val min: DoubleArray, val max: DoubleArray, val triangles: Int, val volume: Double, val area: Double) {
    val size: DoubleArray get() = DoubleArray(3) { max[it] - min[it] }

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
    }

    private class Collector {
        val min = DoubleArray(3) { Double.POSITIVE_INFINITY }
        val max = DoubleArray(3) { Double.NEGATIVE_INFINITY }
        var triangles = 0
        var volume = 0.0
        var area = 0.0

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
            area += sqrt(nx * nx + ny * ny + nz * nz) / 2.0
        }

        fun result(): MeshStats? = if (triangles == 0) null else MeshStats(min, max, triangles, abs(volume), area)
    }
}
