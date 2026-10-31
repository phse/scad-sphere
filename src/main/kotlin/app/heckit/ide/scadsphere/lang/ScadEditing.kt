package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadFunctionDeclaration
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import app.heckit.ide.scadsphere.psi.ScadParameter
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil

class ScadBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(ScadTypes.LBRACE, ScadTypes.RBRACE, true),
            BracePair(ScadTypes.LPAREN, ScadTypes.RPAREN, false),
            BracePair(ScadTypes.LBRACKET, ScadTypes.RBRACKET, false),
        )
    }
}

class ScadCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = ScadTypes.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = ScadTypes.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType = ScadTypes.DOC_COMMENT
    override fun getDocumentationCommentPrefix(): String = "/**"
    override fun getDocumentationCommentLinePrefix(): String = "*"
    override fun getDocumentationCommentSuffix(): String = "*/"
    override fun isDocumentationComment(element: com.intellij.psi.PsiComment): Boolean = element.tokenType == ScadTypes.DOC_COMMENT
}

class ScadQuoteHandler : SimpleTokenSetQuoteHandler(ScadTypes.STRING)

/** Blöcke, mehrzeilige Kommentare und lange Vektoren (z. B. Punktlisten von Polygonen) zuklappen. */
class ScadFoldingBuilder : FoldingBuilderEx() {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val result = mutableListOf<FoldingDescriptor>()
        PsiTreeUtil.processElements(root) { element ->
            val type = element.node.elementType
            val range = element.textRange
            val multiLine = document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)
            if (multiLine && range.length > 2 && (type == ScadTypes.BLOCK || type in ScadTypes.COMMENTS && type != ScadTypes.LINE_COMMENT ||
                    type == ScadTypes.VECTOR_EXPRESSION || type == ScadTypes.ARGUMENT_LIST && document.getLineNumber(range.endOffset) - document.getLineNumber(range.startOffset) > 2)
            ) {
                result += FoldingDescriptor(element.node, range)
            }
            true
        }
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.elementType) {
        ScadTypes.BLOCK -> "{…}"
        ScadTypes.VECTOR_EXPRESSION -> "[…]"
        ScadTypes.ARGUMENT_LIST -> "(…)"
        ScadTypes.DOC_COMMENT -> "/**…*/"
        else -> "/*…*/"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

class ScadFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        ScadLexer(), TokenSet.create(ScadTypes.IDENTIFIER), ScadTypes.COMMENTS, ScadTypes.STRINGS,
    )

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is ScadNamedElement && element.nameIdentifier != null
    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = ScadSphereBundle.message(
        when (element) {
            is ScadModuleDeclaration -> "kind.module"
            is ScadFunctionDeclaration -> "kind.function"
            is ScadParameter -> "kind.parameter"
            is ScadAssignment, is ScadArgument -> "kind.variable"
            else -> "kind.variable"
        },
    )

    override fun getDescriptiveName(element: PsiElement): String = (element as? ScadNamedElement)?.name ?: ""
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = (element as? ScadNamedElement)?.signature() ?: ""
}

class ScadRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean = element is ScadNamedElement
}

class ScadNamesValidator : NamesValidator {
    override fun isKeyword(name: String, project: Project?): Boolean = name in ScadTypes.KEYWORDS
    override fun isIdentifier(name: String, project: Project?): Boolean =
        name.isNotEmpty() && name !in ScadTypes.KEYWORDS && ScadLexer.isIdentifierStart(name[0]) &&
            name.drop(1).all(ScadLexer::isIdentifierPart)
}

/** TODO/FIXME in Kommentaren (TODO-Fenster und Hervorhebung). */
class ScadIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? = if (file.language == ScadLanguage) ScadLexer() else null
    override fun getCommentTokenSet(file: PsiFile): TokenSet? = if (file.language == ScadLanguage) ScadTypes.COMMENTS else null
    override fun getCommentStartDelta(tokenType: IElementType?): Int = if (tokenType == ScadTypes.DOC_COMMENT) 3 else 2
    override fun getCommentEndDelta(tokenType: IElementType?): Int = if (tokenType == ScadTypes.LINE_COMMENT) 0 else 2
}
