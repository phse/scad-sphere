package app.heckit.ide.scadsphere.editor

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.customizer.formatNumber
import app.heckit.ide.scadsphere.lang.ScadTypes
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Korrekturen aus der Druckprüfung, direkt in die .scad geschrieben (eine Undo-Einheit): Stützsäulen unter Stellen, an
 * denen eine Schicht in der Luft beginnt, und eine andere Druckausrichtung als `translate(…) rotate(…)` vor jedem
 * Geometrie-Aufruf der obersten Ebene.
 */
object PrintFixes {
    val PILLAR_MODULE = """// Breakaway support pillar (SCAD Sphere): stands on the bed or on the part below and ends
// gap below the spot where a layer would start in mid-air. Snap it off after printing.
module support_pillar(x, y, from, to, d = 2, gap = 0.2) {
    h = to - from - gap;
    translate([x, y, from]) {
        cylinder(d1 = 3 * d, d2 = d, h = min(2, h / 4), ${'$'}fn = 16);
        cylinder(d = d, h = h, ${'$'}fn = 16);
    }
}
"""

    /** Aufrufe für Säulen `[x, y, von, bis]` oder `[x, y, von, bis, Durchmesser]` (Koordinaten der Datei). */
    fun pillarCalls(pillars: List<DoubleArray>): String =
        pillars.joinToString("\n", postfix = "\n") { p ->
            val d = p.getOrNull(4)?.takeIf { abs(it - 2.0) > 1e-6 }?.let { ", d = ${n(it)}" } ?: ""
            "support_pillar(${n(p[0])}, ${n(p[1])}, ${n(p[2])}, ${n(p[3])}$d);"
        }

    /** Säulen ans Dateiende; das Modul nur, wenn es noch fehlt. */
    fun insertPillars(project: Project, file: VirtualFile, pillars: List<DoubleArray>) {
        if (pillars.isEmpty()) return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        WriteCommandAction.writeCommandAction(project).withName(ScadSphereBundle.message("print.pillars.command")).run<RuntimeException> {
            val text = document.charsSequence
            val module = if (Regex("""\bmodule\s+support_pillar\s*\(""").containsMatchIn(text)) "" else "\n" + PILLAR_MODULE
            val tail = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
            document.insertString(document.textLength, tail + module + "\n" + pillarCalls(pillars))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        notify(project, ScadSphereBundle.message("print.pillars.done", pillars.size))
    }

    /**
     * Matrix (3×4, zeilenweise) als `translate([…]) rotate(…) ` - Drehung um eine Achse als `rotate([90, 0, 0])`, sonst
     * `rotate(a = …, v = […])`; null für keine Änderung.
     */
    fun transformPrefix(m: DoubleArray): String? {
        val r = arrayOf(doubleArrayOf(m[0], m[1], m[2]), doubleArrayOf(m[4], m[5], m[6]), doubleArrayOf(m[8], m[9], m[10]))
        val t = doubleArrayOf(m[3], m[7], m[11])
        val trace = r[0][0] + r[1][1] + r[2][2]
        val angle = Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
        var axis = doubleArrayOf(r[2][1] - r[1][2], r[0][2] - r[2][0], r[1][0] - r[0][1])
        var length = sqrt(axis.sumOf { it * it })
        if (length < 1e-6 && angle > 90) {
            // 180°: Achse aus der Diagonalen, Vorzeichen aus den übrigen Einträgen
            val x = sqrt(((r[0][0] + 1) / 2).coerceAtLeast(0.0))
            var y = sqrt(((r[1][1] + 1) / 2).coerceAtLeast(0.0))
            var z = sqrt(((r[2][2] + 1) / 2).coerceAtLeast(0.0))
            if (x > 1e-6) {
                if (r[0][1] < 0) y = -y
                if (r[0][2] < 0) z = -z
            } else if (y > 1e-6 && r[1][2] < 0) z = -z
            axis = doubleArrayOf(x, y, z)
            length = sqrt(axis.sumOf { it * it })
        }
        val parts = mutableListOf<String>()
        if (t.any { abs(it) > 5e-4 }) parts += "translate([${t.joinToString(", ") { n(it) }}])"
        if (angle > 0.01 && length > 1e-9) {
            val unit = axis.map { it / length }
            val principal = unit.indexOfFirst { abs(abs(it) - 1) < 1e-6 }
            parts += if (principal >= 0) {
                val angles = DoubleArray(3).also { it[principal] = angle * Math.signum(unit[principal]) }
                "rotate([${angles.joinToString(", ") { n(it) }}])"
            } else {
                "rotate(a = ${n(angle)}, v = [${unit.joinToString(", ") { formatNumber(Math.round(it * 10000) / 10000.0) }}])"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ", postfix = " ")
    }

    private const val NUM = """(-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)"""

    /** `translate([…]) rotate(…) ` am Anfang eines Aufrufs, wie [transformPrefix] es schreibt (auch mit eigenen Zahlen). */
    private val OWN_PREFIX = Regex(
        """^translate\(\[\s*$NUM\s*,\s*$NUM\s*,\s*$NUM\s*]\)\s*rotate\(\s*(?:\[\s*$NUM\s*,\s*$NUM\s*,\s*$NUM\s*]""" +
            """|a\s*=\s*$NUM\s*,\s*v\s*=\s*\[\s*$NUM\s*,\s*$NUM\s*,\s*$NUM\s*])\s*\)\s*""",
    )

    /** Vorangestellte Drehung samt Verschiebung am Anfang von [text] als Matrix (3×4) und Länge des Texts, oder null. */
    fun parsePrefix(text: CharSequence): Pair<DoubleArray, Int>? {
        val match = OWN_PREFIX.find(text) ?: return null
        val g = match.groupValues
        val t = doubleArrayOf(g[1].toDouble(), g[2].toDouble(), g[3].toDouble())
        val r = if (g[4].isNotEmpty()) {
            // rotate([x, y, z]) dreht in OpenSCAD erst um X, dann Y, dann Z
            multiply(rotation(2, g[6].toDouble()), multiply(rotation(1, g[5].toDouble()), rotation(0, g[4].toDouble())))
        } else {
            axisAngle(g[7].toDouble(), doubleArrayOf(g[8].toDouble(), g[9].toDouble(), g[10].toDouble()))
        }
        return doubleArrayOf(r[0], r[1], r[2], t[0], r[4], r[5], r[6], t[1], r[8], r[9], r[10], t[2]) to match.value.length
    }

    /** [a] nach [b] (beide 3×4, zeilenweise; die vierte Zeile ist 0 0 0 1). */
    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(12) { i ->
        val row = i / 4
        val col = i % 4
        var sum = (0..2).sumOf { k -> a[row * 4 + k] * b[k * 4 + col] }
        if (col == 3) sum += a[row * 4 + 3]
        sum
    }

    private fun rotation(axis: Int, degrees: Double): DoubleArray {
        val c = kotlin.math.cos(Math.toRadians(degrees))
        val s = kotlin.math.sin(Math.toRadians(degrees))
        return when (axis) {
            0 -> doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, c, -s, 0.0, 0.0, s, c, 0.0)
            1 -> doubleArrayOf(c, 0.0, s, 0.0, 0.0, 1.0, 0.0, 0.0, -s, 0.0, c, 0.0)
            else -> doubleArrayOf(c, -s, 0.0, 0.0, s, c, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        }
    }

    private fun axisAngle(degrees: Double, v: DoubleArray): DoubleArray {
        val length = sqrt(v.sumOf { it * it })
        if (length < 1e-12) return rotation(0, 0.0)
        val (x, y, z) = v.map { it / length }
        val c = kotlin.math.cos(Math.toRadians(degrees))
        val s = kotlin.math.sin(Math.toRadians(degrees))
        val k = 1 - c
        return doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s, 0.0,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s, 0.0,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k, 0.0,
        )
    }

    /**
     * Neue Druckausrichtung: jedem Geometrie-Aufruf der obersten Ebene die Drehung voranstellen. Steht dort schon eine
     * (von einem früheren Vorschlag), wird sie mit der neuen zusammengefasst statt eine weitere davorzusetzen.
     */
    fun applyOrientation(project: Project, file: VirtualFile, matrix: DoubleArray) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(document)
        val psi = PsiManager.getInstance(project).findFile(file) as? ScadFile ?: return
        val targets = psi.children.filter { child ->
            when (child.node.elementType) {
                ScadTypes.MODULE_CALL -> (child as ScadModuleCall).moduleName !in setOf("echo", "assert")
                ScadTypes.IF_STATEMENT, ScadTypes.BLOCK -> true
                else -> false
            }
        }
        if (targets.isEmpty() || transformPrefix(matrix) == null) return
        // Je Aufruf: Bereich, der ersetzt wird (vorhandene Drehung oder nichts), und der neue Text
        val edits = targets.map { child ->
            val start = child.textRange.startOffset
            val existing = parsePrefix(child.text)
            if (existing != null) Triple(start, existing.second, transformPrefix(multiply(matrix, existing.first)) ?: "")
            else Triple(start, 0, transformPrefix(matrix)!!)
        }
        WriteCommandAction.writeCommandAction(project).withName(ScadSphereBundle.message("print.orient.command")).run<RuntimeException> {
            for ((start, length, text) in edits.sortedByDescending { it.first }) document.replaceString(start, start + length, text)
            documents.commitDocument(document)
        }
        val shown = edits.first().third.trim()
        notify(project, if (shown.isEmpty()) ScadSphereBundle.message("print.orient.reset") else ScadSphereBundle.message("print.orient.done", shown))
    }

    private fun notify(project: Project, text: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("SCAD Sphere").createNotification(text, NotificationType.INFORMATION).notify(project)
    }

    private fun n(value: Double): String {
        val rounded = Math.round(value * 1000) / 1000.0
        return formatNumber(if (rounded == -0.0) 0.0 else rounded)
    }
}
