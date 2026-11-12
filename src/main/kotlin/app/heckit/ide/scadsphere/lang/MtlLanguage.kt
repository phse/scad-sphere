package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import java.awt.Color
import java.util.Locale
import javax.swing.Icon

/**
 * Wavefront-Materialdatei (.mtl), die Farben zu einer .obj: Hervorhebung, Farbfelder mit Farbwähler neben
 * `Kd`/`Ka`/`Ks`/`Ke` und Faltung je `newmtl`. Ohne eigenen Dateityp bot die IDE beim Öffnen fremde Plugins an.
 */
object MtlLanguage : Language("heckit.MTL") {
    override fun getDisplayName(): String = "MTL"
}

object MtlFileType : LanguageFileType(MtlLanguage) {
    override fun getName(): String = "heckit.MTL"
    override fun getDisplayName(): String = "MTL"
    override fun getDescription(): String = ScadSphereBundle.message("filetype.mtl.description")
    override fun getDefaultExtension(): String = "mtl"
    override fun getIcon(): Icon = ScadIcons.MODEL
}

object MtlTypes {
    val COMMENT = IElementType("MTL_COMMENT", MtlLanguage)
    val NEWMTL = IElementType("MTL_NEWMTL", MtlLanguage)
    val KEYWORD = IElementType("MTL_KEYWORD", MtlLanguage)
    val NUMBER = IElementType("MTL_NUMBER", MtlLanguage)
    val VALUE = IElementType("MTL_VALUE", MtlLanguage)
    val FILE = IFileElementType(MtlLanguage)

    /** Schlüssel mit einer Farbe (r g b) dahinter. */
    val COLOR_KEYWORDS = setOf("Kd", "Ka", "Ks", "Ke", "Tf")
}

/** Zeilenweise: erstes Wort Schlüssel, danach Zahlen bzw. Werte (Namen, Dateien), `#` bis Zeilenende Kommentar. */
class MtlLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var end = 0
    private var start = 0
    private var tokenEnd = 0
    private var type: IElementType? = null
    private var lineStart = true

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.end = endOffset
        this.tokenEnd = startOffset
        this.lineStart = true
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = type
    override fun getTokenStart(): Int = start
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = end

    override fun advance() {
        start = tokenEnd
        if (start >= end) {
            type = null
            return
        }
        val c = buffer[start]
        var i = start
        when {
            c == '\n' || c == '\r' || c == ' ' || c == '\t' -> {
                while (i < end && buffer[i].isWhitespace()) {
                    if (buffer[i] == '\n') lineStart = true
                    i++
                }
                type = TokenType.WHITE_SPACE
            }
            c == '#' -> {
                while (i < end && buffer[i] != '\n') i++
                type = MtlTypes.COMMENT
            }
            else -> {
                while (i < end && !buffer[i].isWhitespace() && buffer[i] != '#') i++
                val word = buffer.subSequence(start, i).toString()
                type = when {
                    lineStart && word == "newmtl" -> MtlTypes.NEWMTL
                    lineStart -> MtlTypes.KEYWORD
                    word.toDoubleOrNull() != null -> MtlTypes.NUMBER
                    else -> MtlTypes.VALUE
                }
                lineStart = false
            }
        }
        tokenEnd = i
    }
}

class MtlParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = MtlLexer()
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = MtlTypes.FILE
    override fun getCommentTokens(): TokenSet = TokenSet.create(MtlTypes.COMMENT)
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = throw UnsupportedOperationException(node.elementType.toString())
    override fun createFile(viewProvider: FileViewProvider): PsiFile = MtlFile(viewProvider)
}

class MtlFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MtlLanguage) {
    override fun getFileType(): FileType = MtlFileType
}

class MtlSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = MtlLexer()
    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        MtlTypes.COMMENT -> pack(COMMENT)
        MtlTypes.NEWMTL -> pack(KEYWORD)
        MtlTypes.KEYWORD -> pack(PROPERTY)
        MtlTypes.NUMBER -> pack(NUMBER)
        MtlTypes.VALUE -> pack(VALUE)
        else -> TextAttributesKey.EMPTY_ARRAY
    }

    companion object {
        val COMMENT = TextAttributesKey.createTextAttributesKey("MTL_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
        val KEYWORD = TextAttributesKey.createTextAttributesKey("MTL_NEWMTL", DefaultLanguageHighlighterColors.KEYWORD)
        val PROPERTY = TextAttributesKey.createTextAttributesKey("MTL_KEYWORD", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
        val NUMBER = TextAttributesKey.createTextAttributesKey("MTL_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
        val VALUE = TextAttributesKey.createTextAttributesKey("MTL_VALUE", DefaultLanguageHighlighterColors.STRING)
    }
}

class MtlSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = MtlSyntaxHighlighter()
}

class MtlCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "# "
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Faltung je Material: `newmtl name` bleibt sichtbar, die Werte darunter klappen ein. */
class MtlFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val text = document.charsSequence
        val result = mutableListOf<FoldingDescriptor>()
        var blockStart = -1
        fun close(endLine: Int) {
            if (blockStart < 0) return
            var last = endLine
            while (last > blockStart && text.subSequence(document.getLineStartOffset(last), document.getLineEndOffset(last)).isBlank()) last--
            if (last > blockStart) {
                val range = TextRange(document.getLineEndOffset(blockStart), document.getLineEndOffset(last))
                result += FoldingDescriptor(root.node, range)
            }
        }
        for (line in 0 until document.lineCount) {
            val content = text.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString().trim()
            if (content.startsWith("newmtl ")) {
                close(line - 1)
                blockStart = line
            }
        }
        close(document.lineCount - 1)
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = " ..."
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/** Farbfeld neben `Kd 0.9 0.9 0.9` (und Ka/Ks/Ke/Tf) mit Farbwähler; schreibt drei Zahlen 0…1 zurück. */
class MtlColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element.node?.elementType != MtlTypes.KEYWORD || element.text !in MtlTypes.COLOR_KEYWORDS) return null
        val numbers = numbers(element)
        if (numbers.size < 3) return null
        val values = numbers.take(3).map { it.text.toDoubleOrNull() ?: return null }
        if (values.any { it < 0 || it > 1 }) return null
        return Color(values[0].toFloat(), values[1].toFloat(), values[2].toFloat())
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        val numbers = numbers(element).take(3)
        if (numbers.size < 3) return
        val file = element.containingFile ?: return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return
        val range = TextRange(numbers.first().textRange.startOffset, numbers.last().textRange.endOffset)
        val text = listOf(color.red, color.green, color.blue).joinToString(" ") { String.format(Locale.ROOT, "%.3f", it / 255.0) }
        WriteCommandAction.runWriteCommandAction(element.project, ScadSphereBundle.message("color.change"), "ScadSphere.ChangeMtlColor", {
            document.replaceString(range.startOffset, range.endOffset, text)
            PsiDocumentManager.getInstance(element.project).commitDocument(document)
        }, file)
    }

    /** Die Zahlen hinter dem Schlüssel bis zum Zeilenende. */
    private fun numbers(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        var next = element.nextSibling
        while (next != null) {
            val type = next.node.elementType
            if (type == TokenType.WHITE_SPACE) {
                if ('\n' in next.text) break
            } else if (type == MtlTypes.NUMBER) {
                result += next
            } else {
                break
            }
            next = next.nextSibling
        }
        return result
    }
}
