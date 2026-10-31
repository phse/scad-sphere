package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.lang.ScadTypes.ARGUMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.ARGUMENT_LIST
import app.heckit.ide.scadsphere.lang.ScadTypes.ASSERT
import app.heckit.ide.scadsphere.lang.ScadTypes.ASSERT_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.ASSIGN
import app.heckit.ide.scadsphere.lang.ScadTypes.ASSIGNMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.BINARY_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.BLOCK
import app.heckit.ide.scadsphere.lang.ScadTypes.CALL_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.COLON
import app.heckit.ide.scadsphere.lang.ScadTypes.COMMA
import app.heckit.ide.scadsphere.lang.ScadTypes.DOT
import app.heckit.ide.scadsphere.lang.ScadTypes.EACH
import app.heckit.ide.scadsphere.lang.ScadTypes.ECHO
import app.heckit.ide.scadsphere.lang.ScadTypes.ELSE
import app.heckit.ide.scadsphere.lang.ScadTypes.EMPTY_STATEMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.FALSE
import app.heckit.ide.scadsphere.lang.ScadTypes.FOR
import app.heckit.ide.scadsphere.lang.ScadTypes.FUNCTION
import app.heckit.ide.scadsphere.lang.ScadTypes.FUNCTION_DECLARATION
import app.heckit.ide.scadsphere.lang.ScadTypes.FUNCTION_LITERAL
import app.heckit.ide.scadsphere.lang.ScadTypes.IDENTIFIER
import app.heckit.ide.scadsphere.lang.ScadTypes.IF
import app.heckit.ide.scadsphere.lang.ScadTypes.IF_STATEMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.IMPORT_PATH
import app.heckit.ide.scadsphere.lang.ScadTypes.INCLUDE
import app.heckit.ide.scadsphere.lang.ScadTypes.INCLUDE_STATEMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.INDEX_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.LBRACE
import app.heckit.ide.scadsphere.lang.ScadTypes.LBRACKET
import app.heckit.ide.scadsphere.lang.ScadTypes.LC_EACH
import app.heckit.ide.scadsphere.lang.ScadTypes.LC_FOR
import app.heckit.ide.scadsphere.lang.ScadTypes.LC_IF
import app.heckit.ide.scadsphere.lang.ScadTypes.LC_LET
import app.heckit.ide.scadsphere.lang.ScadTypes.LET
import app.heckit.ide.scadsphere.lang.ScadTypes.LET_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.LITERAL_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.LPAREN
import app.heckit.ide.scadsphere.lang.ScadTypes.MEMBER_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.MINUS
import app.heckit.ide.scadsphere.lang.ScadTypes.MODULE
import app.heckit.ide.scadsphere.lang.ScadTypes.MODULE_CALL
import app.heckit.ide.scadsphere.lang.ScadTypes.MODULE_DECLARATION
import app.heckit.ide.scadsphere.lang.ScadTypes.NOT
import app.heckit.ide.scadsphere.lang.ScadTypes.NUMBER
import app.heckit.ide.scadsphere.lang.ScadTypes.PARAMETER
import app.heckit.ide.scadsphere.lang.ScadTypes.PARAMETER_LIST
import app.heckit.ide.scadsphere.lang.ScadTypes.PAREN_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.PLUS
import app.heckit.ide.scadsphere.lang.ScadTypes.POW
import app.heckit.ide.scadsphere.lang.ScadTypes.QUESTION
import app.heckit.ide.scadsphere.lang.ScadTypes.RANGE_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.RBRACE
import app.heckit.ide.scadsphere.lang.ScadTypes.RBRACKET
import app.heckit.ide.scadsphere.lang.ScadTypes.REFERENCE_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.RPAREN
import app.heckit.ide.scadsphere.lang.ScadTypes.SEMICOLON
import app.heckit.ide.scadsphere.lang.ScadTypes.STRING
import app.heckit.ide.scadsphere.lang.ScadTypes.TERNARY_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.TILDE
import app.heckit.ide.scadsphere.lang.ScadTypes.TRUE
import app.heckit.ide.scadsphere.lang.ScadTypes.UNARY_EXPRESSION
import app.heckit.ide.scadsphere.lang.ScadTypes.UNDEF
import app.heckit.ide.scadsphere.lang.ScadTypes.USE
import app.heckit.ide.scadsphere.lang.ScadTypes.USE_STATEMENT
import app.heckit.ide.scadsphere.lang.ScadTypes.VECTOR_EXPRESSION
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * Rekursiv absteigender Parser nach der Grammatik von OpenSCAD (parser.y). Jede Schleife verbraucht pro Runde
 * mindestens ein Token oder bricht ab, damit unvollständiger Code beim Tippen nie hängen bleibt.
 */
class ScadParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val file = builder.mark()
        val parsing = Parsing(builder)
        while (!builder.eof()) parsing.statement(topLevel = true)
        file.done(root)
        return builder.treeBuilt
    }

    private class Parsing(private val b: PsiBuilder) {
        private val tt: IElementType? get() = b.tokenType

        private fun message(key: String) = ScadSphereBundle.message(key)

        private fun expect(type: IElementType, key: String): Boolean {
            if (tt == type) {
                b.advanceLexer()
                return true
            }
            b.error(message(key))
            return false
        }

        /** Unerwartetes Token als Fehler überspringen. */
        private fun skipWithError(key: String) {
            val error = b.mark()
            b.advanceLexer()
            error.error(message(key))
        }

        // --- Anweisungen ---

        fun statement(topLevel: Boolean = false) {
            when (tt) {
                SEMICOLON -> {
                    val m = b.mark()
                    b.advanceLexer()
                    m.done(EMPTY_STATEMENT)
                }
                LBRACE -> block()
                MODULE -> moduleDeclaration()
                FUNCTION -> functionDeclaration()
                INCLUDE, USE -> importStatement()
                IDENTIFIER -> if (b.lookAhead(1) == ASSIGN) assignment() else moduleInstantiation()
                RBRACE -> if (topLevel) skipWithError("parser.unexpected") else return
                else -> if (startsModuleInstantiation()) moduleInstantiation() else skipWithError("parser.unexpected")
            }
        }

        private fun startsModuleInstantiation() = tt in ScadTypes.MODULE_NAMES || tt == IF || tt in ScadTypes.MODIFIERS

        private fun block() {
            val m = b.mark()
            b.advanceLexer()
            while (!b.eof() && tt != RBRACE) statement()
            expect(RBRACE, "parser.expected.rbrace")
            m.done(BLOCK)
        }

        private fun importStatement() {
            val m = b.mark()
            val type = if (tt == INCLUDE) INCLUDE_STATEMENT else USE_STATEMENT
            b.advanceLexer()
            expect(IMPORT_PATH, "parser.expected.path")
            m.done(type)
        }

        private fun assignment() {
            val m = b.mark()
            b.advanceLexer()
            b.advanceLexer()
            endOfStatement(expression())
            m.done(ASSIGNMENT)
        }

        /**
         * `;` nach einem Ausdruck. Fehlt er oder fehlt der Ausdruck, wird der Rest der Zeile als ein Fehler
         * übersprungen - die nächste Zeile bleibt unangetastet (beim Tippen fehlt das `;` oft nur kurz).
         */
        private fun endOfStatement(hasExpression: Boolean) {
            if (hasExpression && tt == SEMICOLON) {
                b.advanceLexer()
                return
            }
            val key = if (hasExpression) "parser.expected.semicolon" else "parser.expected.expression"
            val error = b.mark()
            var skipped = false
            while (!b.eof() && tt != SEMICOLON && tt != RBRACE && !atLineStart()) {
                b.advanceLexer()
                skipped = true
            }
            if (skipped) {
                error.error(message(key))
            } else {
                error.drop()
                b.error(message(key))
            }
            if (tt == SEMICOLON) b.advanceLexer()
        }

        /** Steht das aktuelle Token am Anfang einer Zeile? */
        private fun atLineStart(): Boolean {
            if (b.rawLookup(-1) != TokenType.WHITE_SPACE) return false
            val start = b.rawTokenTypeStart(-1)
            return b.originalText.subSequence(start, b.currentOffset).contains('\n')
        }

        private fun moduleDeclaration() {
            val m = b.mark()
            b.advanceLexer()
            if (tt == IDENTIFIER) b.advanceLexer() else b.error(message("parser.expected.name"))
            if (tt == LPAREN) parameterList() else b.error(message("parser.expected.lparen"))
            if (!b.eof() && tt != RBRACE) childStatement() else b.error(message("parser.expected.body"))
            m.done(MODULE_DECLARATION)
        }

        private fun functionDeclaration() {
            val m = b.mark()
            b.advanceLexer()
            if (tt == IDENTIFIER) b.advanceLexer() else b.error(message("parser.expected.name"))
            if (tt == LPAREN) parameterList() else b.error(message("parser.expected.lparen"))
            if (expect(ASSIGN, "parser.expected.assign")) endOfStatement(expression()) else expect(SEMICOLON, "parser.expected.semicolon")
            m.done(FUNCTION_DECLARATION)
        }

        private fun ifStatement() {
            val m = b.mark()
            b.advanceLexer()
            condition()
            childStatement()
            if (tt == ELSE) {
                b.advanceLexer()
                childStatement()
            }
            m.done(IF_STATEMENT)
        }

        private fun condition() {
            if (!expect(LPAREN, "parser.expected.lparen")) return
            if (!expression()) b.error(message("parser.expected.expression"))
            expect(RPAREN, "parser.expected.rparen")
        }

        /** Modulaufruf mit optionalen Modifikatoren (# ! % *), auch `if` und for/let/echo/assert. */
        private fun moduleInstantiation() {
            val m = b.mark()
            var modifiers = false
            while (tt in ScadTypes.MODIFIERS) {
                b.advanceLexer()
                modifiers = true
            }
            when {
                tt == IF -> {
                    ifStatement()
                    // Nur mit Modifikator (`# if (…) …`) als eigener Aufruf, sonst direkt die if-Anweisung
                    if (modifiers) m.done(MODULE_CALL) else m.drop()
                    return
                }
                tt in ScadTypes.MODULE_NAMES -> {
                    b.advanceLexer()
                    if (tt == LPAREN) argumentList() else b.error(message("parser.expected.lparen"))
                    childStatement()
                }
                else -> b.error(message("parser.expected.module"))
            }
            m.done(MODULE_CALL)
        }

        /** Was auf einen Modulaufruf folgt: `;`, ein Block oder ein weiterer Aufruf (Kind). */
        private fun childStatement() {
            when {
                tt == SEMICOLON -> {
                    val m = b.mark()
                    b.advanceLexer()
                    m.done(EMPTY_STATEMENT)
                }
                tt == LBRACE -> block()
                tt == IDENTIFIER && b.lookAhead(1) == ASSIGN -> b.error(message("parser.expected.semicolon"))
                startsModuleInstantiation() -> moduleInstantiation()
                else -> b.error(message("parser.expected.semicolon"))
            }
        }

        // --- Parameter und Argumente ---

        private fun parameterList() {
            val m = b.mark()
            b.advanceLexer()
            while (!b.eof() && tt != RPAREN) {
                when (tt) {
                    COMMA -> b.advanceLexer()
                    IDENTIFIER -> {
                        val p = b.mark()
                        b.advanceLexer()
                        if (tt == ASSIGN) {
                            b.advanceLexer()
                            if (!expression()) b.error(message("parser.expected.expression"))
                        }
                        p.done(PARAMETER)
                        if (tt != COMMA && tt != RPAREN) b.error(message("parser.expected.commaOrRparen"))
                    }
                    in STOP -> break
                    else -> skipWithError("parser.expected.parameter")
                }
            }
            expect(RPAREN, "parser.expected.rparen")
            m.done(PARAMETER_LIST)
        }

        /**
         * `( a, b = 1, … )` - auch der Kopf von for-Schleifen. [cStyle]: bei Listen-for auch `;` als Trenner
         * (`for (i = 0; i < 10; i = i + 1)`).
         */
        fun argumentList(cStyle: Boolean = false) {
            val m = b.mark()
            b.advanceLexer()
            while (!b.eof() && tt != RPAREN) {
                if (tt == COMMA || cStyle && tt == SEMICOLON) {
                    b.advanceLexer()
                    continue
                }
                if (tt in STOP) break
                val a = b.mark()
                val ok = if (tt == IDENTIFIER && b.lookAhead(1) == ASSIGN) {
                    b.advanceLexer()
                    b.advanceLexer()
                    if (!expression()) b.error(message("parser.expected.expression"))
                    true
                } else {
                    expression()
                }
                if (!ok) {
                    a.drop()
                    skipWithError("parser.expected.expression")
                    continue
                }
                a.done(ARGUMENT)
                if (tt != COMMA && tt != RPAREN && !(cStyle && tt == SEMICOLON)) {
                    b.error(message("parser.expected.commaOrRparen"))
                    if (tt in STOP) break
                }
            }
            expect(RPAREN, "parser.expected.rparen")
            m.done(ARGUMENT_LIST)
        }

        // --- Ausdrücke ---

        fun expression(): Boolean {
            when (tt) {
                FUNCTION -> {
                    val m = b.mark()
                    b.advanceLexer()
                    if (tt == LPAREN) parameterList() else b.error(message("parser.expected.lparen"))
                    if (!expression()) b.error(message("parser.expected.expression"))
                    m.done(FUNCTION_LITERAL)
                    return true
                }
                LET -> {
                    val m = b.mark()
                    b.advanceLexer()
                    if (tt == LPAREN) argumentList() else b.error(message("parser.expected.lparen"))
                    if (!expression()) b.error(message("parser.expected.expression"))
                    m.done(LET_EXPRESSION)
                    return true
                }
                ASSERT, ECHO -> {
                    val m = b.mark()
                    b.advanceLexer()
                    if (tt == LPAREN) argumentList() else b.error(message("parser.expected.lparen"))
                    // Ausdruck danach ist optional: `x = assert(a > 0);`
                    if (!b.eof() && tt !in EXPRESSION_END) expression()
                    m.done(ASSERT_EXPRESSION)
                    return true
                }
                else -> {}
            }
            val m = b.mark()
            if (!binary(0)) {
                m.drop()
                return false
            }
            if (tt == QUESTION) {
                b.advanceLexer()
                if (!expression()) b.error(message("parser.expected.expression"))
                if (expect(COLON, "parser.expected.colon")) {
                    if (!expression()) b.error(message("parser.expected.expression"))
                }
                m.done(TERNARY_EXPRESSION)
            } else {
                m.drop()
            }
            return true
        }

        private fun binary(level: Int): Boolean {
            if (level == BINARY_LEVELS.size) return unary()
            var m = b.mark()
            if (!binary(level + 1)) {
                m.drop()
                return false
            }
            while (tt in BINARY_LEVELS[level]) {
                b.advanceLexer()
                if (!binary(level + 1)) b.error(message("parser.expected.expression"))
                m.done(BINARY_EXPRESSION)
                m = m.precede()
            }
            m.drop()
            return true
        }

        private fun unary(): Boolean {
            if (tt == PLUS || tt == MINUS || tt == NOT || tt == TILDE) {
                val m = b.mark()
                b.advanceLexer()
                if (!unary()) b.error(message("parser.expected.expression"))
                m.done(UNARY_EXPRESSION)
                return true
            }
            return exponent()
        }

        /** `^` bindet stärker als ein Vorzeichen davor (`-2^2` = -4) und ist rechtsassoziativ. */
        private fun exponent(): Boolean {
            val m = b.mark()
            if (!postfix()) {
                m.drop()
                return false
            }
            if (tt == POW) {
                b.advanceLexer()
                if (!unary()) b.error(message("parser.expected.expression"))
                m.done(BINARY_EXPRESSION)
            } else {
                m.drop()
            }
            return true
        }

        private fun postfix(): Boolean {
            var m = b.mark()
            if (!primary()) {
                m.drop()
                return false
            }
            while (true) {
                when (tt) {
                    LPAREN -> {
                        argumentList()
                        m.done(CALL_EXPRESSION)
                    }
                    LBRACKET -> {
                        b.advanceLexer()
                        if (!expression()) b.error(message("parser.expected.expression"))
                        expect(RBRACKET, "parser.expected.rbracket")
                        m.done(INDEX_EXPRESSION)
                    }
                    DOT -> {
                        b.advanceLexer()
                        expect(IDENTIFIER, "parser.expected.name")
                        m.done(MEMBER_EXPRESSION)
                    }
                    else -> break
                }
                m = m.precede()
            }
            m.drop()
            return true
        }

        private fun primary(): Boolean {
            val m = b.mark()
            when (tt) {
                TRUE, FALSE, UNDEF, NUMBER, STRING -> {
                    b.advanceLexer()
                    m.done(LITERAL_EXPRESSION)
                }
                IDENTIFIER -> {
                    b.advanceLexer()
                    m.done(REFERENCE_EXPRESSION)
                }
                LPAREN -> {
                    b.advanceLexer()
                    if (!expression()) b.error(message("parser.expected.expression"))
                    expect(RPAREN, "parser.expected.rparen")
                    m.done(PAREN_EXPRESSION)
                }
                LBRACKET -> {
                    m.drop()
                    vector()
                }
                else -> {
                    m.drop()
                    return false
                }
            }
            return true
        }

        /** `[ ]`, Vektor, Bereich `[a : b]` / `[a : step : b]` oder Listen-Comprehension. */
        private fun vector() {
            val m = b.mark()
            b.advanceLexer()
            var first = true
            while (!b.eof() && tt != RBRACKET) {
                if (tt == COMMA) {
                    b.advanceLexer()
                    first = false
                    continue
                }
                if (tt in STOP) break
                if (!vectorElement()) {
                    skipWithError("parser.expected.expression")
                    first = false
                    continue
                }
                if (first && tt == COLON) {
                    b.advanceLexer()
                    if (!expression()) b.error(message("parser.expected.expression"))
                    if (tt == COLON) {
                        b.advanceLexer()
                        if (!expression()) b.error(message("parser.expected.expression"))
                    }
                    expect(RBRACKET, "parser.expected.rbracket")
                    m.done(RANGE_EXPRESSION)
                    return
                }
                first = false
                if (tt != COMMA && tt != RBRACKET) {
                    b.error(message("parser.expected.commaOrRbracket"))
                    if (tt in STOP) break
                }
            }
            expect(RBRACKET, "parser.expected.rbracket")
            m.done(VECTOR_EXPRESSION)
        }

        /** Element eines Vektors: Ausdruck oder for/if/let/each der Listen-Comprehension. */
        private fun vectorElement(): Boolean {
            val m = b.mark()
            when (tt) {
                FOR -> {
                    b.advanceLexer()
                    if (tt == LPAREN) argumentList(cStyle = true) else b.error(message("parser.expected.lparen"))
                    if (!vectorElement()) b.error(message("parser.expected.expression"))
                    m.done(LC_FOR)
                }
                IF -> {
                    b.advanceLexer()
                    condition()
                    if (!vectorElement()) b.error(message("parser.expected.expression"))
                    if (tt == ELSE) {
                        b.advanceLexer()
                        if (!vectorElement()) b.error(message("parser.expected.expression"))
                    }
                    m.done(LC_IF)
                }
                LET -> {
                    b.advanceLexer()
                    if (tt == LPAREN) argumentList() else b.error(message("parser.expected.lparen"))
                    if (!vectorElement()) b.error(message("parser.expected.expression"))
                    m.done(LC_LET)
                }
                EACH -> {
                    b.advanceLexer()
                    if (!vectorElement()) b.error(message("parser.expected.expression"))
                    m.done(LC_EACH)
                }
                LPAREN -> {
                    // `(for …)` in Klammern - sonst ein normaler Ausdruck in Klammern
                    if (b.lookAhead(1) in COMPREHENSION_START) {
                        b.advanceLexer()
                        vectorElement()
                        expect(RPAREN, "parser.expected.rparen")
                        m.done(PAREN_EXPRESSION)
                    } else {
                        m.drop()
                        return expression()
                    }
                }
                else -> {
                    m.drop()
                    return expression()
                }
            }
            return true
        }
    }

    companion object {
        private val BINARY_LEVELS: List<TokenSet> = listOf(
            TokenSet.create(ScadTypes.OR),
            TokenSet.create(ScadTypes.AND),
            TokenSet.create(ScadTypes.EQ, ScadTypes.NE),
            TokenSet.create(ScadTypes.LT, ScadTypes.LE, ScadTypes.GT, ScadTypes.GE),
            TokenSet.create(ScadTypes.BIT_OR),
            TokenSet.create(ScadTypes.BIT_AND),
            TokenSet.create(ScadTypes.SHL, ScadTypes.SHR),
            TokenSet.create(ScadTypes.PLUS, ScadTypes.MINUS),
            TokenSet.create(ScadTypes.MUL, ScadTypes.DIV, ScadTypes.MOD),
        )

        /** Hier endet jede Liste: Fehler nicht darüber hinweg auffangen. */
        private val STOP = TokenSet.create(SEMICOLON, LBRACE, RBRACE)

        private val EXPRESSION_END = TokenSet.create(SEMICOLON, RPAREN, RBRACKET, RBRACE, COMMA, COLON)

        private val COMPREHENSION_START = TokenSet.create(FOR, IF, LET, EACH)
    }
}
