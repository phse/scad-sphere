package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadPsiFactory
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class ScadParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = ScadLexer()
    override fun createParser(project: Project?): PsiParser = ScadParser()
    override fun getFileNodeType(): IFileElementType = ScadTypes.FILE
    override fun getCommentTokens(): TokenSet = ScadTypes.COMMENTS
    override fun getWhitespaceTokens(): TokenSet = ScadTypes.WHITESPACES
    override fun getStringLiteralElements(): TokenSet = ScadTypes.STRINGS
    override fun createElement(node: ASTNode): PsiElement = ScadPsiFactory.create(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = ScadFile(viewProvider)
}
