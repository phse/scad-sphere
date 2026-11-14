package app.heckit.ide.scadsphere.render

import app.heckit.ide.scadsphere.lang.ScadLexer
import app.heckit.ide.scadsphere.lang.ScadTypes

/**
 * OpenSCAD nennt bei `echo` weder Datei noch Zeile. Für die Vorschau bekommt deshalb jedes `echo(…)` der Datei als
 * erstes Argument eine Marke mit seiner Zeile (`echo("@@scadsphere:12", …)`), die beim Lesen der Ausgabe wieder
 * entfernt wird. Die Marke steht in derselben Zeile, Zeilennummern in Fehlermeldungen ändern sich also nicht.
 * Nur für den Lauf der Vorschau - Exporte und die Datei selbst bleiben unberührt.
 */
object EchoLines {
    private const val MARK = "@@scadsphere:"
    private val OUTPUT = Regex("""^"@@scadsphere:(\d+)"(?:, )?""")

    /** [text] mit Zeilenmarken in jedem `echo(…)` (Anweisung und Ausdruck); ohne `echo` unverändert. */
    fun instrument(text: String): String {
        if (!text.contains("echo")) return text
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

    /** Ausgabe eines `echo` (ohne `ECHO: `): Zeile aus der Marke und der Rest; null ohne Marke. */
    fun parse(output: String): Pair<Int, String>? {
        val match = OUTPUT.find(output) ?: return null
        val line = match.groupValues[1].toIntOrNull() ?: return null
        return line to output.substring(match.range.last + 1)
    }
}
