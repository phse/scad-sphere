package app.heckit.ide.scadsphere.render

import app.heckit.ide.scadsphere.render.CsgOverlay.Node
import kotlin.math.roundToInt

/**
 * Ein Modell nach Farben zerlegen, für den Mehrfarbdruck: Aus dem CSG-Baum (`-o tree.csg`, alle Module und Schleifen
 * schon ausgewertet) entsteht je Farbe ein eigener Baum, den OpenSCAD zu einem geschlossenen Körper rechnet.
 *
 * Regeln wie in OpenSCAD: Das äußere `color()` gilt für alles darunter. In Vereinigungen (Gruppen, `union`,
 * Verschiebungen, `color`) bleiben nur die Kinder der gesuchten Farbe. Bei `difference` entscheidet das erste Kind über
 * die Farbe, abgezogen wird alles Übrige, egal in welcher Farbe. Was sich nicht in Teile zerlegen lässt (`hull`,
 * `intersection`, `minkowski`, Extrusionen, `resize`, …), gehört als Ganzes zu seiner Farbe: der äußeren, sonst der
 * ersten darin. Teile ohne Farbe bilden eine eigene Gruppe ([NO_COLOR]); `%`- und `*`-Teile werden nicht gedruckt.
 */
object ColorParts {
    /** Schlüssel der Teile ohne `color()` */
    const val NO_COLOR = "-"

    class Part(
        /** `rrggbb` (klein), [NO_COLOR] ohne Farbe */
        val color: String,
        /** CSG-Text nur dieser Farbe, für OpenSCAD über stdin */
        val source: String,
    )

    /** Teile in der Reihenfolge, in der die Farben im Modell zuerst vorkommen; null, wenn der Baum nicht lesbar ist. */
    fun split(csg: String): List<Part>? {
        val nodes = runCatching { CsgOverlay.parse(csg) }.getOrNull() ?: return null
        val colors = LinkedHashSet<String>()
        collectColors(nodes, null, colors)
        return colors.mapNotNull { color ->
            val kept = nodes.mapNotNull { filter(it, null, color) }
            if (kept.isEmpty()) null else Part(color, kept.joinToString("\n") { write(it) } + "\n")
        }
    }

    /** `color([0.27451, 0.509804, 0.705882, 1])` → `4682b4`; null bei anderen Köpfen oder Farben ohne Zahlen. */
    fun colorOf(head: String): String? {
        val match = COLOR.find(head) ?: return null
        val parts = match.groupValues[1].split(',').map { it.trim().toDoubleOrNull() ?: return null }
        if (parts.size < 3) return null
        return parts.take(3).joinToString("") { "%02x".format((it.coerceIn(0.0, 1.0) * 255).roundToInt()) }
    }

    private fun printed(node: Node) = '%' !in node.modifiers && '*' !in node.modifiers

    private fun kind(node: Node): Kind {
        val name = node.head.substringBefore('(').trim()
        return when {
            node.children == null -> Kind.LEAF
            name == "difference" -> Kind.DIFFERENCE
            name in UNIONS -> Kind.UNION
            else -> Kind.ATOMIC
        }
    }

    /** Farbe eines unteilbaren Knotens: die äußere, sonst die erste darin, sonst ohne Farbe. */
    private fun atomicColor(node: Node, inherited: String?): String = inherited ?: firstColor(node) ?: NO_COLOR

    private fun firstColor(node: Node): String? {
        colorOf(node.head)?.let { return it }
        for (child in node.children.orEmpty()) if (printed(child)) firstColor(child)?.let { return it }
        return null
    }

    private fun collectColors(nodes: List<Node>, inherited: String?, out: MutableSet<String>) {
        for (node in nodes) {
            if (!printed(node)) continue
            val color = inherited ?: colorOf(node.head)
            when (kind(node)) {
                Kind.LEAF, Kind.ATOMIC -> out += atomicColor(node, inherited)
                Kind.UNION -> collectColors(node.children!!, color, out)
                Kind.DIFFERENCE -> node.children!!.firstOrNull { printed(it) }?.let { collectColors(listOf(it), color, out) }
            }
        }
    }

    /** Teilbaum, soweit er zur Farbe [target] gehört; null, wenn nichts davon. */
    private fun filter(node: Node, inherited: String?, target: String): Node? {
        if (!printed(node)) return null
        val color = inherited ?: colorOf(node.head)
        return when (kind(node)) {
            Kind.LEAF, Kind.ATOMIC -> node.takeIf { atomicColor(node, inherited) == target }
            Kind.UNION -> {
                val kept = node.children!!.mapNotNull { filter(it, color, target) }
                if (kept.isEmpty()) null else Node("", node.head, kept)
            }
            Kind.DIFFERENCE -> {
                val children = node.children!!.filter { printed(it) }
                val first = children.firstOrNull()?.let { filter(it, color, target) } ?: return null
                Node("", node.head, listOf(first) + children.drop(1))
            }
        }
    }

    /** Teilbaum als CSG-Text; `#` fällt weg (gedruckt wird es trotzdem), `%`/`*` sind schon aussortiert. */
    private fun write(node: Node): String {
        val children = node.children ?: return "${node.head};"
        return "${node.head} {\n${children.filter { printed(it) }.joinToString("\n") { write(it) }}\n}"
    }

    private enum class Kind { LEAF, UNION, DIFFERENCE, ATOMIC }

    private val UNIONS = setOf("group", "union", "multmatrix", "color", "render", "root")
    private val COLOR = Regex("""^color\(\s*\[([^\]]*)]""")
}
