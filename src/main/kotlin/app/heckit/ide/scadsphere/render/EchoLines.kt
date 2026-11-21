package app.heckit.ide.scadsphere.render

import app.heckit.ide.scadsphere.lang.ScadLexer
import app.heckit.ide.scadsphere.lang.ScadTypes

/**
 * OpenSCAD nennt bei `echo` weder Datei noch Zeile. Für die Vorschau bekommt deshalb jedes `echo(…)` der Datei als
 * erstes Argument eine Marke mit seiner Zeile (`echo("@@scadsphere:12", …)`), die beim Lesen der Ausgabe wieder
 * entfernt wird. Die Marke steht in derselben Zeile, Zeilennummern in Fehlermeldungen ändern sich also nicht.
 * Nur für den Lauf der Vorschau - Exporte und die Datei selbst bleiben unberührt.
 *
 * Mit [instrument] `values = true` bekommt außerdem jede gerechnete Zuweisung auf oberster Ebene und direkt in einem
 * Modul ein `echo("@@scadsphere=12", name);` hinter ihr Semikolon (gleiche Zeile): Der Editor zeigt so den Wert am
 * Zeilenende (`spacing = (length - 70) / (hooks - 1)` → `34`). Zuweisungen von bloßen Zahlen, Texten oder Listen
 * daraus bleiben ohne - ihr Wert steht schon da.
 */
object EchoLines {
    private const val MARK = "@@scadsphere:"
    private const val VALUE_MARK = "@@scadsphere="
    private val OUTPUT = Regex("""^"@@scadsphere:(\d+)"(?:, )?""")
    private val VALUE_OUTPUT = Regex("""^"@@scadsphere=(\d+)", ?""")
    private val LITERALS = setOf(
        ScadTypes.NUMBER, ScadTypes.STRING, ScadTypes.TRUE, ScadTypes.FALSE, ScadTypes.UNDEF, ScadTypes.MINUS, ScadTypes.PLUS,
        ScadTypes.LBRACKET, ScadTypes.RBRACKET, ScadTypes.COMMA,
    )

    /** [text] mit Zeilenmarken in jedem `echo(…)` (Anweisung und Ausdruck), mit [values] auch hinter Zuweisungen; sonst unverändert. */
    @JvmOverloads
    fun instrument(text: String, values: Boolean = false): String {
        val withEcho = if (text.contains("echo")) markEchoes(text) else text
        return if (values) markValues(withEcho) else withEcho
    }

    private fun markEchoes(text: String): String {
        val lexer = ScadLexer()
        lexer.start(text, 0, text.length, 0)
        val result = StringBuilder(text.length + 64)
        var copied = 0
        var line = 1
        var counted = 0
        // 0: nichts, 1: echo gesehen, 2: Klammer auf gesehen (Marke kommt vor das nächste Zeichen)
        var state = 0
        var echoLine = 0
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != ScadTypes.LINE_COMMENT && type != ScadTypes.BLOCK_COMMENT && type != ScadTypes.DOC_COMMENT && type !in ScadTypes.WHITESPACES) {
                when {
                    state == 2 -> {
                        result.append(text, copied, lexer.tokenStart)
                        copied = lexer.tokenStart
                        result.append('"').append(MARK).append(echoLine).append('"')
                        if (type != ScadTypes.RPAREN) result.append(", ")
                        state = 0
                    }
                    state == 1 -> state = if (type == ScadTypes.LPAREN) 2 else 0
                }
                if (type == ScadTypes.ECHO) {
                    while (counted < lexer.tokenStart) { if (text[counted] == '\n') line++; counted++ }
                    echoLine = line
                    state = 1
                }
            }
            lexer.advance()
        }
        result.append(text, copied, text.length)
        return result.toString()
    }

    /**
     * `echo` mit dem Wert hinter jede gerechnete Zuweisung (Anweisung auf oberster Ebene oder direkt im Rumpf eines Moduls,
     * nicht in Schleifen und Verzweigungen - die liefen sonst bei großen Schleifen tausendfach).
     */
    private fun markValues(text: String): String {
        val lexer = ScadLexer()
        lexer.start(text, 0, text.length, 0)
        val result = StringBuilder(text.length + 256)
        var copied = 0
        var line = 1
        var counted = 0
        var parens = 0
        // Offene Blöcke: true = Rumpf eines Moduls
        val blocks = ArrayDeque<Boolean>()
        var moduleHeader = false
        var previous: com.intellij.psi.tree.IElementType? = null
        // Laufende Zuweisung: Name, Zeile, nur Literale bisher
        var name: String? = null
        var nameLine = 0
        var sawAssign = false
        var literal = true
        var candidate: String? = null
        // Funktionsliteral: echo gäbe nur den Quelltext wieder
        var functionValue = false
        while (true) {
            val type = lexer.tokenType ?: break
            if (type in ScadTypes.COMMENTS || type in ScadTypes.WHITESPACES) { lexer.advance(); continue }
            val atStatement = parens == 0 && (previous == null || previous == ScadTypes.SEMICOLON || previous == ScadTypes.LBRACE || previous == ScadTypes.RBRACE)
            val allowed = blocks.isEmpty() || blocks.last()
            when {
                atStatement && type == ScadTypes.IDENTIFIER && allowed -> { candidate = text.substring(lexer.tokenStart, lexer.tokenEnd); name = null }
                candidate != null && previous == ScadTypes.IDENTIFIER && type == ScadTypes.ASSIGN && parens == 0 -> {
                    name = candidate
                    while (counted < lexer.tokenStart) { if (text[counted] == '\n') line++; counted++ }
                    nameLine = line
                    sawAssign = true
                    literal = true
                    candidate = null
                }
                else -> candidate = null
            }
            when (type) {
                ScadTypes.MODULE -> moduleHeader = true
                ScadTypes.FUNCTION -> if (sawAssign) functionValue = true
                ScadTypes.LPAREN, ScadTypes.LBRACKET -> parens++
                ScadTypes.RPAREN, ScadTypes.RBRACKET -> parens = (parens - 1).coerceAtLeast(0)
                ScadTypes.LBRACE -> if (parens == 0) { blocks.addLast(moduleHeader); moduleHeader = false }
                ScadTypes.RBRACE -> if (parens == 0) blocks.removeLastOrNull()
                else -> {}
            }
            if (sawAssign && type != ScadTypes.ASSIGN && type != ScadTypes.SEMICOLON && type !in LITERALS) literal = false
            if (type == ScadTypes.SEMICOLON && parens == 0) {
                if (sawAssign && name != null && !literal && !functionValue) {
                    result.append(text, copied, lexer.tokenEnd)
                    copied = lexer.tokenEnd
                    result.append(" echo(\"").append(VALUE_MARK).append(nameLine).append("\", ").append(name).append(");")
                }
                sawAssign = false
                functionValue = false
                name = null
                moduleHeader = false
            }
            previous = type
            lexer.advance()
        }
        result.append(text, copied, text.length)
        return result.toString()
    }

    /** Ausgabe der Wert-Marke (ohne `ECHO: `): Zeile der Zuweisung und der Wert; null ohne diese Marke. */
    fun parseValue(output: String): Pair<Int, String>? {
        val match = VALUE_OUTPUT.find(output) ?: return null
        val line = match.groupValues[1].toIntOrNull() ?: return null
        return line to output.substring(match.range.last + 1)
    }

    /** Ausgabe eines `echo` (ohne `ECHO: `): Zeile aus der Marke und der Rest; null ohne Marke. */
    fun parse(output: String): Pair<Int, String>? {
        val match = OUTPUT.find(output) ?: return null
        val line = match.groupValues[1].toIntOrNull() ?: return null
        return line to output.substring(match.range.last + 1)
    }
}
