package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

object ScadColors {
    val KEYWORD = key("KEYWORD", Default.KEYWORD)
    val NUMBER = key("NUMBER", Default.NUMBER)
    val STRING = key("STRING", Default.STRING)
    val IMPORT_PATH = key("IMPORT_PATH", Default.STRING)
    val LINE_COMMENT = key("LINE_COMMENT", Default.LINE_COMMENT)
    val BLOCK_COMMENT = key("BLOCK_COMMENT", Default.BLOCK_COMMENT)
    val DOC_COMMENT = key("DOC_COMMENT", Default.DOC_COMMENT)
    val OPERATOR = key("OPERATOR", Default.OPERATION_SIGN)
    val PARENTHESES = key("PARENTHESES", Default.PARENTHESES)
    val BRACKETS = key("BRACKETS", Default.BRACKETS)
    val BRACES = key("BRACES", Default.BRACES)
    val SEMICOLON = key("SEMICOLON", Default.SEMICOLON)
    val COMMA = key("COMMA", Default.COMMA)
    val DOT = key("DOT", Default.DOT)
    val IDENTIFIER = key("IDENTIFIER", Default.IDENTIFIER)
    val BAD_CHARACTER = key("BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    // Aus der Bedeutung (Annotator)
    val MODULE_DECLARATION = key("MODULE_DECLARATION", Default.FUNCTION_DECLARATION)
    val FUNCTION_DECLARATION = key("FUNCTION_DECLARATION", Default.FUNCTION_DECLARATION)
    val MODULE_CALL = key("MODULE_CALL", Default.FUNCTION_CALL)
    val FUNCTION_CALL = key("FUNCTION_CALL", Default.FUNCTION_CALL)
    val BUILTIN_MODULE = key("BUILTIN_MODULE", Default.PREDEFINED_SYMBOL)
    val BUILTIN_FUNCTION = key("BUILTIN_FUNCTION", Default.STATIC_METHOD)
    val PARAMETER = key("PARAMETER", Default.PARAMETER)
    val NAMED_ARGUMENT = key("NAMED_ARGUMENT", Default.INSTANCE_FIELD)
    val SPECIAL_VARIABLE = key("SPECIAL_VARIABLE", Default.STATIC_FIELD)
    val MODIFIER = key("MODIFIER", Default.METADATA)

    private fun key(name: String, fallback: TextAttributesKey) = createTextAttributesKey("SCADSPHERE_$name", fallback)
}

class ScadSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ScadLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = pack(
        when (tokenType) {
            in ScadTypes.KEYWORD_SET -> ScadColors.KEYWORD
            ScadTypes.NUMBER -> ScadColors.NUMBER
            ScadTypes.STRING -> ScadColors.STRING
            ScadTypes.IMPORT_PATH -> ScadColors.IMPORT_PATH
            ScadTypes.LINE_COMMENT -> ScadColors.LINE_COMMENT
            ScadTypes.BLOCK_COMMENT -> ScadColors.BLOCK_COMMENT
            ScadTypes.DOC_COMMENT -> ScadColors.DOC_COMMENT
            ScadTypes.LPAREN, ScadTypes.RPAREN -> ScadColors.PARENTHESES
            ScadTypes.LBRACKET, ScadTypes.RBRACKET -> ScadColors.BRACKETS
            ScadTypes.LBRACE, ScadTypes.RBRACE -> ScadColors.BRACES
            ScadTypes.SEMICOLON -> ScadColors.SEMICOLON
            ScadTypes.COMMA -> ScadColors.COMMA
            ScadTypes.DOT -> ScadColors.DOT
            in ScadTypes.OPERATORS -> ScadColors.OPERATOR
            ScadTypes.IDENTIFIER -> ScadColors.IDENTIFIER
            TokenType.BAD_CHARACTER -> ScadColors.BAD_CHARACTER
            else -> null
        },
    )
}

class ScadSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = ScadSyntaxHighlighter()
}

class ScadColorSettingsPage : ColorSettingsPage {
    private val descriptors = arrayOf(
        descriptor("keyword", ScadColors.KEYWORD),
        descriptor("number", ScadColors.NUMBER),
        descriptor("string", ScadColors.STRING),
        descriptor("importPath", ScadColors.IMPORT_PATH),
        descriptor("lineComment", ScadColors.LINE_COMMENT),
        descriptor("blockComment", ScadColors.BLOCK_COMMENT),
        descriptor("docComment", ScadColors.DOC_COMMENT),
        descriptor("operator", ScadColors.OPERATOR),
        descriptor("parentheses", ScadColors.PARENTHESES),
        descriptor("brackets", ScadColors.BRACKETS),
        descriptor("braces", ScadColors.BRACES),
        descriptor("semicolon", ScadColors.SEMICOLON),
        descriptor("comma", ScadColors.COMMA),
        descriptor("dot", ScadColors.DOT),
        descriptor("identifier", ScadColors.IDENTIFIER),
        descriptor("moduleDeclaration", ScadColors.MODULE_DECLARATION),
        descriptor("functionDeclaration", ScadColors.FUNCTION_DECLARATION),
        descriptor("moduleCall", ScadColors.MODULE_CALL),
        descriptor("functionCall", ScadColors.FUNCTION_CALL),
        descriptor("builtinModule", ScadColors.BUILTIN_MODULE),
        descriptor("builtinFunction", ScadColors.BUILTIN_FUNCTION),
        descriptor("parameter", ScadColors.PARAMETER),
        descriptor("namedArgument", ScadColors.NAMED_ARGUMENT),
        descriptor("specialVariable", ScadColors.SPECIAL_VARIABLE),
        descriptor("modifier", ScadColors.MODIFIER),
        descriptor("badCharacter", ScadColors.BAD_CHARACTER),
    )

    private fun descriptor(key: String, attributes: TextAttributesKey) =
        AttributesDescriptor(ScadSphereBundle.messagePointer("color.$key"), attributes)

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = descriptors
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getDisplayName(): String = "OpenSCAD"
    override fun getIcon(): Icon = ScadIcons.SCAD
    override fun getHighlighter(): SyntaxHighlighter = ScadSyntaxHighlighter()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "mdecl" to ScadColors.MODULE_DECLARATION,
        "fdecl" to ScadColors.FUNCTION_DECLARATION,
        "mcall" to ScadColors.MODULE_CALL,
        "fcall" to ScadColors.FUNCTION_CALL,
        "bmod" to ScadColors.BUILTIN_MODULE,
        "bfun" to ScadColors.BUILTIN_FUNCTION,
        "param" to ScadColors.PARAMETER,
        "named" to ScadColors.NAMED_ARGUMENT,
        "special" to ScadColors.SPECIAL_VARIABLE,
        "mod" to ScadColors.MODIFIER,
    )

    override fun getDemoText(): String = """
        include <BOSL2/std.scad>

        /** Box with a lid groove. */
        wall = 2;     // [1:0.5:4]
        <special>${'$'}fn</special> = 64;

        module <mdecl>box</mdecl>(<param>size</param> = [40, 30, 20], <param>rounding</param> = 3) {
            <bmod>difference</bmod>() {
                <bmod>cube</bmod>(<param>size</param>, <named>center</named> = true);
                <mod>#</mod><bmod>translate</bmod>([0, 0, wall]) <mcall>inner</mcall>(<param>size</param> - [wall, wall, 0] * 2);
            }
        }

        function <fdecl>area</fdecl>(<param>s</param>) = <param>s</param>.x * <param>s</param>[1];
        points = [for (i = [0 : 5]) [<bfun>cos</bfun>(i * 60), <bfun>sin</bfun>(i * 60)] * 10];

        <mcall>box</mcall>();
        <bmod>echo</bmod>(<fcall>area</fcall>([4, 5]), "mm²");
        bad = 1 @ 2;
    """.trimIndent()
}
