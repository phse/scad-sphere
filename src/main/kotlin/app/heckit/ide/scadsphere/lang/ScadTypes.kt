package app.heckit.ide.scadsphere.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class ScadTokenType(debugName: String) : IElementType(debugName, ScadLanguage)

class ScadElementType(debugName: String) : IElementType(debugName, ScadLanguage)

object ScadTypes {
    @JvmField val FILE = IFileElementType(ScadLanguage)

    // --- Tokens ---
    @JvmField val IDENTIFIER = ScadTokenType("IDENTIFIER")
    @JvmField val NUMBER = ScadTokenType("NUMBER")
    @JvmField val STRING = ScadTokenType("STRING")
    /** `<datei.scad>` nach include/use */
    @JvmField val IMPORT_PATH = ScadTokenType("IMPORT_PATH")
    @JvmField val LINE_COMMENT = ScadTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = ScadTokenType("BLOCK_COMMENT")
    @JvmField val DOC_COMMENT = ScadTokenType("DOC_COMMENT")

    @JvmField val MODULE = ScadTokenType("module")
    @JvmField val FUNCTION = ScadTokenType("function")
    @JvmField val IF = ScadTokenType("if")
    @JvmField val ELSE = ScadTokenType("else")
    @JvmField val FOR = ScadTokenType("for")
    @JvmField val LET = ScadTokenType("let")
    @JvmField val EACH = ScadTokenType("each")
    @JvmField val ASSERT = ScadTokenType("assert")
    @JvmField val ECHO = ScadTokenType("echo")
    @JvmField val INCLUDE = ScadTokenType("include")
    @JvmField val USE = ScadTokenType("use")
    @JvmField val TRUE = ScadTokenType("true")
    @JvmField val FALSE = ScadTokenType("false")
    @JvmField val UNDEF = ScadTokenType("undef")

    @JvmField val LPAREN = ScadTokenType("(")
    @JvmField val RPAREN = ScadTokenType(")")
    @JvmField val LBRACKET = ScadTokenType("[")
    @JvmField val RBRACKET = ScadTokenType("]")
    @JvmField val LBRACE = ScadTokenType("{")
    @JvmField val RBRACE = ScadTokenType("}")
    @JvmField val SEMICOLON = ScadTokenType(";")
    @JvmField val COMMA = ScadTokenType(",")
    @JvmField val DOT = ScadTokenType(".")
    @JvmField val COLON = ScadTokenType(":")
    @JvmField val QUESTION = ScadTokenType("?")
    @JvmField val ASSIGN = ScadTokenType("=")
    @JvmField val EQ = ScadTokenType("==")
    @JvmField val NE = ScadTokenType("!=")
    @JvmField val LT = ScadTokenType("<")
    @JvmField val LE = ScadTokenType("<=")
    @JvmField val GT = ScadTokenType(">")
    @JvmField val GE = ScadTokenType(">=")
    @JvmField val AND = ScadTokenType("&&")
    @JvmField val OR = ScadTokenType("||")
    @JvmField val NOT = ScadTokenType("!")
    @JvmField val PLUS = ScadTokenType("+")
    @JvmField val MINUS = ScadTokenType("-")
    @JvmField val MUL = ScadTokenType("*")
    @JvmField val DIV = ScadTokenType("/")
    @JvmField val MOD = ScadTokenType("%")
    @JvmField val POW = ScadTokenType("^")
    @JvmField val HASH = ScadTokenType("#")
    // Bit-Operatoren (neuere OpenSCAD-Versionen)
    @JvmField val TILDE = ScadTokenType("~")
    @JvmField val BIT_AND = ScadTokenType("&")
    @JvmField val BIT_OR = ScadTokenType("|")
    @JvmField val SHL = ScadTokenType("<<")
    @JvmField val SHR = ScadTokenType(">>")

    // --- Elemente ---
    @JvmField val INCLUDE_STATEMENT = ScadElementType("INCLUDE_STATEMENT")
    @JvmField val USE_STATEMENT = ScadElementType("USE_STATEMENT")
    @JvmField val MODULE_DECLARATION = ScadElementType("MODULE_DECLARATION")
    @JvmField val FUNCTION_DECLARATION = ScadElementType("FUNCTION_DECLARATION")
    @JvmField val ASSIGNMENT = ScadElementType("ASSIGNMENT")
    @JvmField val PARAMETER_LIST = ScadElementType("PARAMETER_LIST")
    @JvmField val PARAMETER = ScadElementType("PARAMETER")
    @JvmField val ARGUMENT_LIST = ScadElementType("ARGUMENT_LIST")
    @JvmField val ARGUMENT = ScadElementType("ARGUMENT")
    /** Modulaufruf als Anweisung, auch for/let/echo/assert und mit Modifikatoren (# ! % *) */
    @JvmField val MODULE_CALL = ScadElementType("MODULE_CALL")
    @JvmField val IF_STATEMENT = ScadElementType("IF_STATEMENT")
    @JvmField val BLOCK = ScadElementType("BLOCK")
    @JvmField val EMPTY_STATEMENT = ScadElementType("EMPTY_STATEMENT")

    @JvmField val REFERENCE_EXPRESSION = ScadElementType("REFERENCE_EXPRESSION")
    @JvmField val LITERAL_EXPRESSION = ScadElementType("LITERAL_EXPRESSION")
    @JvmField val CALL_EXPRESSION = ScadElementType("CALL_EXPRESSION")
    @JvmField val INDEX_EXPRESSION = ScadElementType("INDEX_EXPRESSION")
    @JvmField val MEMBER_EXPRESSION = ScadElementType("MEMBER_EXPRESSION")
    @JvmField val PAREN_EXPRESSION = ScadElementType("PAREN_EXPRESSION")
    @JvmField val UNARY_EXPRESSION = ScadElementType("UNARY_EXPRESSION")
    @JvmField val BINARY_EXPRESSION = ScadElementType("BINARY_EXPRESSION")
    @JvmField val TERNARY_EXPRESSION = ScadElementType("TERNARY_EXPRESSION")
    @JvmField val VECTOR_EXPRESSION = ScadElementType("VECTOR_EXPRESSION")
    @JvmField val RANGE_EXPRESSION = ScadElementType("RANGE_EXPRESSION")
    @JvmField val FUNCTION_LITERAL = ScadElementType("FUNCTION_LITERAL")
    @JvmField val LET_EXPRESSION = ScadElementType("LET_EXPRESSION")
    /** assert(...) bzw. echo(...) vor einem Ausdruck */
    @JvmField val ASSERT_EXPRESSION = ScadElementType("ASSERT_EXPRESSION")
    @JvmField val LC_FOR = ScadElementType("LC_FOR")
    @JvmField val LC_IF = ScadElementType("LC_IF")
    @JvmField val LC_LET = ScadElementType("LC_LET")
    @JvmField val LC_EACH = ScadElementType("LC_EACH")

    val KEYWORDS: Map<String, IElementType> = listOf(
        MODULE, FUNCTION, IF, ELSE, FOR, LET, EACH, ASSERT, ECHO, INCLUDE, USE, TRUE, FALSE, UNDEF,
    ).associateBy { it.toString() }

    @JvmField val KEYWORD_SET = TokenSet.create(*KEYWORDS.values.toTypedArray())
    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    @JvmField val WHITESPACES = TokenSet.create(TokenType.WHITE_SPACE)
    @JvmField val STRINGS = TokenSet.create(STRING)
    @JvmField val OPERATORS = TokenSet.create(
        ASSIGN, EQ, NE, LT, LE, GT, GE, AND, OR, NOT, PLUS, MINUS, MUL, DIV, MOD, POW, QUESTION, COLON,
        TILDE, BIT_AND, BIT_OR, SHL, SHR, HASH,
    )
    /** Namen, die als Modulaufruf vorkommen können (for, let, … sind Schlüsselwörter, aber Module) */
    @JvmField val MODULE_NAMES = TokenSet.create(IDENTIFIER, FOR, LET, EACH, ASSERT, ECHO)
    @JvmField val MODIFIERS = TokenSet.create(HASH, NOT, MOD, MUL)
}
