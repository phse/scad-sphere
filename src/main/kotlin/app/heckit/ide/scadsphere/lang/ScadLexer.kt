package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.lang.ScadTypes.AND
import app.heckit.ide.scadsphere.lang.ScadTypes.ASSIGN
import app.heckit.ide.scadsphere.lang.ScadTypes.BIT_AND
import app.heckit.ide.scadsphere.lang.ScadTypes.BIT_OR
import app.heckit.ide.scadsphere.lang.ScadTypes.BLOCK_COMMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.COLON
import app.heckit.ide.scadsphere.lang.ScadTypes.COMMA
import app.heckit.ide.scadsphere.lang.ScadTypes.DIV
import app.heckit.ide.scadsphere.lang.ScadTypes.DOC_COMMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.DOT
import app.heckit.ide.scadsphere.lang.ScadTypes.EQ
import app.heckit.ide.scadsphere.lang.ScadTypes.GE
import app.heckit.ide.scadsphere.lang.ScadTypes.GT
import app.heckit.ide.scadsphere.lang.ScadTypes.HASH
import app.heckit.ide.scadsphere.lang.ScadTypes.IDENTIFIER
import app.heckit.ide.scadsphere.lang.ScadTypes.IMPORT_PATH
import app.heckit.ide.scadsphere.lang.ScadTypes.INCLUDE
import app.heckit.ide.scadsphere.lang.ScadTypes.LBRACE
import app.heckit.ide.scadsphere.lang.ScadTypes.LBRACKET
import app.heckit.ide.scadsphere.lang.ScadTypes.LE
import app.heckit.ide.scadsphere.lang.ScadTypes.LINE_COMMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.LPAREN
import app.heckit.ide.scadsphere.lang.ScadTypes.LT
import app.heckit.ide.scadsphere.lang.ScadTypes.MINUS
import app.heckit.ide.scadsphere.lang.ScadTypes.MOD
import app.heckit.ide.scadsphere.lang.ScadTypes.MUL
import app.heckit.ide.scadsphere.lang.ScadTypes.NE
import app.heckit.ide.scadsphere.lang.ScadTypes.NOT
import app.heckit.ide.scadsphere.lang.ScadTypes.NUMBER
import app.heckit.ide.scadsphere.lang.ScadTypes.OR
import app.heckit.ide.scadsphere.lang.ScadTypes.PLUS
import app.heckit.ide.scadsphere.lang.ScadTypes.POW
import app.heckit.ide.scadsphere.lang.ScadTypes.QUESTION
import app.heckit.ide.scadsphere.lang.ScadTypes.RBRACE
import app.heckit.ide.scadsphere.lang.ScadTypes.RBRACKET
import app.heckit.ide.scadsphere.lang.ScadTypes.RPAREN
import app.heckit.ide.scadsphere.lang.ScadTypes.SEMICOLON
import app.heckit.ide.scadsphere.lang.ScadTypes.SHL
import app.heckit.ide.scadsphere.lang.ScadTypes.SHR
import app.heckit.ide.scadsphere.lang.ScadTypes.STRING
import app.heckit.ide.scadsphere.lang.ScadTypes.TILDE
import app.heckit.ide.scadsphere.lang.ScadTypes.USE
import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Handgeschriebener Lexer für OpenSCAD. Einziger Zustand: Direkt nach `include`/`use` ist `<…>` ein Dateipfad
 * ([IMPORT_PATH]) statt eines Kleiner-Zeichens.
 */
class ScadLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    /** Zustand vor dem aktuellen Token (für das Neustarten mitten im Text) */
    private var tokenState = 0
    private var state = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        bufferEnd = endOffset
        tokenEnd = startOffset
        state = initialState
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        tokenState = state
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        tokenType = lex()
    }

    private fun at(offset: Int): Char = if (offset < bufferEnd) buffer[offset] else '\u0000'

    private fun lex(): IElementType {
        val c = buffer[tokenStart]
        var i = tokenStart + 1
        // Leerraum und Kommentare ändern den Zustand nicht
        when {
            c.isWhitespace() -> {
                while (i < bufferEnd && buffer[i].isWhitespace()) i++
                return token(i, TokenType.WHITE_SPACE)
            }
            c == '/' && at(i) == '/' -> {
                while (i < bufferEnd && buffer[i] != '\n' && buffer[i] != '\r') i++
                return token(i, LINE_COMMENT)
            }
            c == '/' && at(i) == '*' -> {
                val doc = at(i + 1) == '*' && at(i + 2) != '/'
                i++
                while (i < bufferEnd && !(buffer[i] == '*' && at(i + 1) == '/')) i++
                i = minOf(i + 2, bufferEnd)
                return token(i, if (doc) DOC_COMMENT else BLOCK_COMMENT)
            }
        }
        if (state == AFTER_IMPORT && c == '<') {
            while (i < bufferEnd && buffer[i] != '>' && buffer[i] != '\n') i++
            if (i < bufferEnd && buffer[i] == '>') i++
            state = INITIAL
            return token(i, IMPORT_PATH)
        }
        state = INITIAL
        return when {
            c == '"' -> {
                while (i < bufferEnd && buffer[i] != '"' && buffer[i] != '\n') {
                    if (buffer[i] == '\\' && i + 1 < bufferEnd && buffer[i + 1] != '\n') i++
                    i++
                }
                if (i < bufferEnd && buffer[i] == '"') i++
                token(i, STRING)
            }
            c.isDigit() || c == '.' && at(i).isDigit() -> number()
            isIdentifierStart(c) -> {
                while (i < bufferEnd && isIdentifierPart(buffer[i])) i++
                val type = ScadTypes.KEYWORDS[buffer.subSequence(tokenStart, i).toString()] ?: IDENTIFIER
                if (type == INCLUDE || type == USE) state = AFTER_IMPORT
                token(i, type)
            }
            else -> operator(c, at(i))
        }
    }

    /** Zahl (auch `.5`, `1e-3`, `0x1F`); geht sie in Buchstaben über (`2d`), ist es ein Name wie in OpenSCAD. */
    private fun number(): IElementType {
        var i = tokenStart
        if (at(i) == '0' && (at(i + 1) == 'x' || at(i + 1) == 'X') && at(i + 2).isHexDigit()) {
            i += 2
            while (at(i).isHexDigit()) i++
        } else {
            while (at(i).isDigit()) i++
            if (at(i) == '.' && at(i + 1).isDigit() || at(i) == '.' && !isIdentifierStart(at(i + 1))) {
                i++
                while (at(i).isDigit()) i++
            }
            if ((at(i) == 'e' || at(i) == 'E') &&
                (at(i + 1).isDigit() || (at(i + 1) == '+' || at(i + 1) == '-') && at(i + 2).isDigit())
            ) {
                i += 2
                while (at(i).isDigit()) i++
            }
        }
        if (i < bufferEnd && isIdentifierPart(buffer[i]) && buffer[i] != '$') {
            while (i < bufferEnd && isIdentifierPart(buffer[i])) i++
            return token(i, IDENTIFIER)
        }
        return token(i, NUMBER)
    }

    private fun operator(c: Char, next: Char): IElementType {
        val two = when ("$c$next") {
            "==" -> EQ
            "!=" -> NE
            "<=" -> LE
            ">=" -> GE
            "&&" -> AND
            "||" -> OR
            "<<" -> SHL
            ">>" -> SHR
            else -> null
        }
        if (two != null) return token(tokenStart + 2, two)
        val one = when (c) {
            '(' -> LPAREN
            ')' -> RPAREN
            '[' -> LBRACKET
            ']' -> RBRACKET
            '{' -> LBRACE
            '}' -> RBRACE
            ';' -> SEMICOLON
            ',' -> COMMA
            '.' -> DOT
            ':' -> COLON
            '?' -> QUESTION
            '=' -> ASSIGN
            '<' -> LT
            '>' -> GT
            '!' -> NOT
            '+' -> PLUS
            '-' -> MINUS
            '*' -> MUL
            '/' -> DIV
            '%' -> MOD
            '^' -> POW
            '#' -> HASH
            '~' -> TILDE
            '&' -> BIT_AND
            '|' -> BIT_OR
            else -> TokenType.BAD_CHARACTER
        }
        return token(tokenStart + 1, one)
    }

    private fun token(end: Int, type: IElementType): IElementType {
        tokenEnd = end
        return type
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    companion object {
        const val INITIAL = 0
        const val AFTER_IMPORT = 1

        fun isIdentifierStart(c: Char) = c == '_' || c == '$' || c.isLetter()
        fun isIdentifierPart(c: Char) = c == '_' || c.isLetterOrDigit()
    }
}
