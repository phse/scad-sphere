package app.heckit.ide.scadsphere.render

/**
 * Teile mit `#` (hervorgehoben) und `%` (Hintergrund): OpenSCAD lässt sie beim Export weg bzw. rechnet sie normal
 * mit, zeigt sie aber nur in der eigenen Vorschau. Aus dem CSG-Baum (`-o tree.csg`, alle Module und Schleifen schon
 * ausgewertet) wird eine zweite Datei mit genau diesen Teilen samt ihrer Verschiebungen gebaut: `#` rot, `%` grau.
 */
object CsgOverlay {
    class Node(val modifiers: String, val head: String, val children: List<Node>?)

    /**
     * Grobe Vorprüfung am Quelltext (ohne Kommentare): `#` oder `%` vor einem Namen. Trifft auch `a % b` - dann
     * kostet es nur einen überflüssigen CSG-Lauf. Farben wie `"#ff0000"` stehen in Zeichenketten und zählen nicht.
     */
    fun mayHaveModifiers(code: String): Boolean {
        val stripped = code.replace(COMMENTS_AND_STRINGS, " ")
        return MODIFIER_IN_SOURCE.containsMatchIn(stripped)
    }

    /** Der Code verwendet `$t` (Animation); Kommentare und Zeichenketten zählen nicht. */
    fun usesTime(code: String): Boolean = TIME.containsMatchIn(code.replace(COMMENTS_AND_STRINGS, " "))

    /** Overlay als SCAD-Text oder null, wenn es keine `#`/`%`-Teile gibt. */
    fun build(csg: String): String? {
        if (!MARKER.containsMatchIn(csg)) return null
        val nodes = runCatching { parse(csg) }.getOrNull() ?: return null
        val highlight = mutableListOf<String>()
        val background = mutableListOf<String>()
        collect(nodes, emptyList(), highlight, background)
        if (highlight.isEmpty() && background.isEmpty()) return null
        return buildString {
            if (highlight.isNotEmpty()) append("color([1, 0, 0, 1]) union() {\n").append(highlight.joinToString("\n")).append("\n}\n")
            if (background.isNotEmpty()) append("color([0.5, 0.5, 0.5, 1]) union() {\n").append(background.joinToString("\n")).append("\n}\n")
        }
    }

    /**
     * Nur Verschiebungen/Drehungen (`multmatrix`) der Eltern bleiben erhalten, CSG-Operationen nicht. Extrusionen
     * bleiben auch: Ein `#`-Teil darin ist 2D und erschiene sonst flach auf z = 0 statt als extrudierter Körper.
     */
    private fun collect(nodes: List<Node>, transforms: List<String>, highlight: MutableList<String>, background: MutableList<String>) {
        for (node in nodes) {
            val target = when {
                '%' in node.modifiers -> background
                '#' in node.modifiers -> highlight
                else -> null
            }
            if (target != null) {
                target += transforms.foldRight(write(node)) { transform, inner -> "$transform {\n$inner\n}" }
                continue
            }
            val children = node.children ?: continue
            val next = if (KEPT.any { node.head.startsWith(it) }) transforms + node.head else transforms
            collect(children, next, highlight, background)
        }
    }

    /** Teilbaum ohne Modifikatoren */
    private fun write(node: Node): String {
        val children = node.children ?: return "${node.head};"
        return "${node.head} {\n${children.joinToString("\n") { write(it) }}\n}"
    }

    fun parse(text: String): List<Node> {
        val parser = Parser(text)
        val nodes = parser.statements()
        parser.skipSpace()
        require(parser.done()) { "unexpected text at ${parser.pos}" }
        return nodes
    }

    private class Parser(val text: String) {
        var pos = 0

        fun done() = pos >= text.length

        fun skipSpace() {
            while (pos < text.length) {
                when {
                    text[pos].isWhitespace() -> pos++
                    text.startsWith("//", pos) -> pos = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
                    text.startsWith("/*", pos) -> pos = text.indexOf("*/", pos).let { if (it < 0) text.length else it + 2 }
                    else -> return
                }
            }
        }

        fun statements(): List<Node> {
            val nodes = mutableListOf<Node>()
            while (true) {
                skipSpace()
                if (done() || text[pos] == '}') return nodes
                if (text[pos] == ';') { pos++; continue }
                nodes += statement()
            }
        }

        fun statement(): Node {
            val modifiers = StringBuilder()
            while (!done() && text[pos] in "#%!*") { modifiers.append(text[pos]); pos++; skipSpace() }
            val start = pos
            while (!done() && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '$')) pos++
            require(pos > start) { "name expected at $pos" }
            skipSpace()
            require(!done() && text[pos] == '(') { "( expected at $pos" }
            skipParens()
            val head = text.substring(start, pos)
            skipSpace()
            return when {
                !done() && text[pos] == ';' -> { pos++; Node(modifiers.toString(), head, null) }
                !done() && text[pos] == '{' -> {
                    pos++
                    val children = statements()
                    require(!done() && text[pos] == '}') { "} expected at $pos" }
                    pos++
                    Node(modifiers.toString(), head, children)
                }
                // Einzelnes Kind ohne Klammern (in CSG-Dateien unüblich)
                else -> Node(modifiers.toString(), head, listOf(statement()))
            }
        }

        /** Argumente in Klammern überspringen, Klammern in Zeichenketten zählen nicht. */
        fun skipParens() {
            var depth = 0
            while (!done()) {
                when (text[pos]) {
                    '"' -> {
                        pos++
                        while (!done() && text[pos] != '"') { if (text[pos] == '\\') pos++; pos++ }
                    }
                    '(', '[' -> depth++
                    ')', ']' -> { depth--; if (depth == 0) { pos++; return } }
                }
                pos++
            }
            error("unbalanced parentheses")
        }
    }

    private val TIME = Regex("""\${'$'}t\b""")
    private val KEPT = listOf("multmatrix(", "linear_extrude(", "rotate_extrude(")
    private val COMMENTS_AND_STRINGS = Regex("""//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"""")
    private val MODIFIER_IN_SOURCE = Regex("""[#%]\s*[A-Za-z_]""")
    private val MARKER = Regex("""(?m)^\s*[#%]""")
}
