package app.heckit.ide.scadsphere.lang

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.formatter.common.AbstractBlock
import com.intellij.psi.tree.TokenSet

/**
 * Formatierer, der nur einrückt: Inhalt von Blöcken und Klammern sowie Kind-Anweisungen ohne Block auf einer
 * eigenen Zeile eine Stufe tiefer. Abstände innerhalb der Zeilen bleiben, wie sie sind.
 */
class ScadFormattingModelBuilder : FormattingModelBuilder {
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val root = ScadBlock(file.node, Indent.getNoneIndent())
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, formattingContext.codeStyleSettings)
    }
}

private class ScadBlock(node: ASTNode, private val indent: Indent) : AbstractBlock(node, null, null as Alignment?) {
    override fun buildChildren(): List<Block> {
        val blocks = mutableListOf<Block>()
        var child = node.firstChildNode
        while (child != null) {
            if (child.elementType != TokenType.WHITE_SPACE && child.textLength > 0) blocks += ScadBlock(child, childIndent(child))
            child = child.treeNext
        }
        return blocks
    }

    private fun childIndent(child: ASTNode): Indent {
        val type = node.elementType
        val childType = child.elementType
        return when {
            type == ScadTypes.BLOCK -> if (childType == ScadTypes.LBRACE || childType == ScadTypes.RBRACE) none() else normal()
            type in BRACKETED -> if (childType in BRACKETS) none() else normal()
            // Kind eines Modulaufrufs, Rumpf ohne Block, Zweige von if/else
            // (`else if` bleibt auf der Höhe des if)
            type in WITH_CHILD_STATEMENT -> if (childType in STATEMENTS && !(type == ScadTypes.IF_STATEMENT && childType == ScadTypes.IF_STATEMENT)) normal() else none()
            // Wert nach `=`, der in der nächsten Zeile weitergeht
            type == ScadTypes.ASSIGNMENT || type == ScadTypes.FUNCTION_DECLARATION || type == ScadTypes.ARGUMENT ||
                type == ScadTypes.PARAMETER -> if (childType in EXPRESSIONS) normal() else none()
            type in COMPREHENSIONS || type == ScadTypes.LET_EXPRESSION || type == ScadTypes.FUNCTION_LITERAL ||
                type == ScadTypes.ASSERT_EXPRESSION -> if (childType in EXPRESSIONS || childType in COMPREHENSIONS) normal() else none()
            else -> none()
        }
    }

    override fun getIndent(): Indent = indent
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun isLeaf(): Boolean = node.firstChildNode == null

    /** Einrückung einer neuen Zeile (Enter) an dieser Stelle. */
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val type = node.elementType
        val indent = when {
            type == ScadTypes.BLOCK || type in BRACKETED -> normal()
            type in WITH_CHILD_STATEMENT || type == ScadTypes.ASSIGNMENT || type == ScadTypes.FUNCTION_DECLARATION ->
                if (isIncomplete) normal() else none()
            else -> none()
        }
        return ChildAttributes(indent, null)
    }

    private fun none() = Indent.getNoneIndent()
    private fun normal() = Indent.getNormalIndent()

    companion object {
        private val BRACKETS = TokenSet.create(
            ScadTypes.LPAREN, ScadTypes.RPAREN, ScadTypes.LBRACKET, ScadTypes.RBRACKET,
        )
        private val BRACKETED = TokenSet.create(
            ScadTypes.ARGUMENT_LIST, ScadTypes.PARAMETER_LIST, ScadTypes.VECTOR_EXPRESSION, ScadTypes.RANGE_EXPRESSION,
            ScadTypes.PAREN_EXPRESSION, ScadTypes.INDEX_EXPRESSION,
        )
        private val WITH_CHILD_STATEMENT = TokenSet.create(
            ScadTypes.MODULE_CALL, ScadTypes.MODULE_DECLARATION, ScadTypes.IF_STATEMENT,
        )
        private val STATEMENTS = TokenSet.create(
            ScadTypes.MODULE_CALL, ScadTypes.IF_STATEMENT, ScadTypes.ASSIGNMENT, ScadTypes.EMPTY_STATEMENT,
        )
        private val COMPREHENSIONS = TokenSet.create(ScadTypes.LC_FOR, ScadTypes.LC_IF, ScadTypes.LC_LET, ScadTypes.LC_EACH)
        private val EXPRESSIONS = TokenSet.create(
            ScadTypes.REFERENCE_EXPRESSION, ScadTypes.LITERAL_EXPRESSION, ScadTypes.CALL_EXPRESSION, ScadTypes.INDEX_EXPRESSION,
            ScadTypes.MEMBER_EXPRESSION, ScadTypes.PAREN_EXPRESSION, ScadTypes.UNARY_EXPRESSION, ScadTypes.BINARY_EXPRESSION,
            ScadTypes.TERNARY_EXPRESSION, ScadTypes.VECTOR_EXPRESSION, ScadTypes.RANGE_EXPRESSION, ScadTypes.FUNCTION_LITERAL,
            ScadTypes.LET_EXPRESSION, ScadTypes.ASSERT_EXPRESSION,
        )
    }
}

/** Settings | Editor | Code Style | OpenSCAD: Tabs und Einrückung. */
class ScadCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = ScadLanguage

    override fun getCodeSample(settingsType: SettingsType): String = """
        module box(size = [40, 30, 20], wall = 2) {
            difference() {
                cube(size);
                translate([wall, wall, wall])
                    cube(size - [2, 2, 1] * wall);
            }
        }

        points = [
            [0, 0],
            [10, 0],
            [5, 8]
        ];
    """.trimIndent()

    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.INDENT_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
    }

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                object : TabbedLanguageCodeStylePanel(ScadLanguage, currentSettings, settings) {
                    override fun initTabs(settings: CodeStyleSettings) {
                        addIndentOptionsTab(settings)
                    }
                }
        }
}
