package app.heckit.ide.scadsphere.psi

import app.heckit.ide.scadsphere.lang.ScadFileType
import app.heckit.ide.scadsphere.lang.ScadLanguage
import app.heckit.ide.scadsphere.lang.ScadTypes
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

class ScadFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, ScadLanguage) {
    override fun getFileType(): FileType = ScadFileType
    override fun toString(): String = "OpenSCAD file"

    val imports: List<ScadImportStatement> get() = children.filterIsInstance<ScadImportStatement>()
}

open class ScadPsiElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    protected fun childOfType(type: IElementType): PsiElement? = node.findChildByType(type)?.psi
}

/** Was ein Name in OpenSCAD sein kann; Module, Funktionen und Variablen haben getrennte Namensräume. */
enum class ScadKind { MODULE, FUNCTION, VARIABLE, PARAMETER }

enum class Namespace { MODULE, FUNCTION, VARIABLE }

val ScadKind.namespace: Namespace
    get() = when (this) {
        ScadKind.MODULE -> Namespace.MODULE
        ScadKind.FUNCTION -> Namespace.FUNCTION
        ScadKind.VARIABLE, ScadKind.PARAMETER -> Namespace.VARIABLE
    }

/** Deklaration mit Namen: Modul, Funktion, Variable, Parameter oder Variable einer Schleife/eines let. */
abstract class ScadNamedElement(node: ASTNode) : ScadPsiElement(node), PsiNameIdentifierOwner {
    abstract val kind: ScadKind

    override fun getNameIdentifier(): PsiElement? = childOfType(ScadTypes.IDENTIFIER)
    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        nameIdentifier?.replace(ScadElementFactory.identifier(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    /**
     * Nur in OpenSCAD-Dateien suchen - nie in PHP, HTML & Co. Lokale Namen (Parameter von Funktionsliteralen,
     * Variablen in Blöcken, Schleifen, let) nur in ihrem Bereich.
     */
    override fun getUseScope(): SearchScope {
        val scope = ScadResolver.localScope(this)
        if (scope != null) return LocalSearchScope(scope)
        return GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(project), ScadFileType)
    }

    open fun icon(): Icon = when (kind) {
        ScadKind.MODULE -> AllIcons.Nodes.Method
        ScadKind.FUNCTION -> AllIcons.Nodes.Function
        ScadKind.VARIABLE -> AllIcons.Nodes.Variable
        ScadKind.PARAMETER -> AllIcons.Nodes.Parameter
    }

    override fun getIcon(flags: Int): Icon = icon()

    /** Name mit Parametern, z. B. `wange(v, l = 10)`. */
    open fun signature(): String = name ?: ""

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = signature()
        override fun getLocationString(): String? = containingFile?.name
        override fun getIcon(unused: Boolean): Icon = icon()
    }
}

/** Gemeinsame Teile von Modul- und Funktionsdeklaration. */
abstract class ScadCallableDeclaration(node: ASTNode) : ScadNamedElement(node) {
    val parameterList: PsiElement? get() = childOfType(ScadTypes.PARAMETER_LIST)
    val parameters: List<ScadParameter> get() = parameterList?.children?.filterIsInstance<ScadParameter>().orEmpty()

    override fun signature(): String = "${name ?: ""}(${parameters.joinToString(", ") { it.signature() }})"
}

class ScadModuleDeclaration(node: ASTNode) : ScadCallableDeclaration(node) {
    override val kind = ScadKind.MODULE
}

class ScadFunctionDeclaration(node: ASTNode) : ScadCallableDeclaration(node) {
    override val kind = ScadKind.FUNCTION
}

class ScadAssignment(node: ASTNode) : ScadNamedElement(node) {
    override val kind = ScadKind.VARIABLE

    val value: PsiElement? get() = children.firstOrNull()

    override fun signature(): String = "${name ?: ""} = ${value?.text?.let(::shorten) ?: ""}"
}

class ScadParameter(node: ASTNode) : ScadNamedElement(node) {
    override val kind = ScadKind.PARAMETER

    val defaultValue: PsiElement? get() = children.firstOrNull { it !is ScadParameter }

    override fun signature(): String = defaultValue?.let { "${name ?: ""} = ${shorten(it.text)}" } ?: (name ?: "")
}

/**
 * Argument eines Aufrufs. Benannt (`r = 2`) ist es bei for/let/intersection_for und in Listen-Comprehensions
 * eine Variablen-Deklaration, sonst ein Verweis auf den Parameter des aufgerufenen Moduls bzw. der Funktion.
 */
class ScadArgument(node: ASTNode) : ScadNamedElement(node) {
    override val kind = ScadKind.VARIABLE

    /** Name vor dem `=`, falls benannt. */
    val argumentName: PsiElement?
        get() = childOfType(ScadTypes.IDENTIFIER)?.takeIf { PsiTreeUtil.skipWhitespacesAndCommentsForward(it)?.node?.elementType == ScadTypes.ASSIGN }

    val value: PsiElement? get() = children.lastOrNull()

    val isDeclaration: Boolean
        get() {
            if (argumentName == null) return false
            val owner = parent?.parent ?: return false
            return when (owner) {
                is ScadModuleCall -> owner.moduleName in SCOPE_MODULES
                else -> owner.node.elementType in DECLARING_OWNERS
            }
        }

    override fun getNameIdentifier(): PsiElement? = if (isDeclaration) argumentName else null
    override fun getName(): String? = argumentName?.text

    override fun getReference(): PsiReference? {
        val name = argumentName ?: return null
        if (isDeclaration) return null
        return ScadParameterReference(this, TextRange.from(name.startOffsetInParent, name.textLength))
    }

    override fun signature(): String = "${name ?: ""} = ${value?.text?.let(::shorten) ?: ""}"

    companion object {
        /** Module, deren benannte Argumente Variablen für die Kinder anlegen */
        val SCOPE_MODULES = setOf("for", "let", "intersection_for", "each")
        private val DECLARING_OWNERS = setOf(ScadTypes.LC_FOR, ScadTypes.LC_LET, ScadTypes.LET_EXPRESSION)
    }
}

class ScadModuleCall(node: ASTNode) : ScadPsiElement(node) {
    /** Name des Moduls (auch for/let/echo/assert); null bei `# if (…)`. */
    val nameElement: PsiElement?
        get() {
            var child = node.firstChildNode
            while (child != null && (child.elementType in ScadTypes.MODIFIERS || child.psi is PsiWhiteSpace || child.psi is PsiComment)) child = child.treeNext
            return child?.takeIf { it.elementType in ScadTypes.MODULE_NAMES }?.psi
        }

    val moduleName: String? get() = nameElement?.text
    val argumentList: PsiElement? get() = childOfType(ScadTypes.ARGUMENT_LIST)
    val arguments: List<ScadArgument> get() = argumentList?.children?.filterIsInstance<ScadArgument>().orEmpty()

    override fun getReference(): PsiReference? {
        val name = nameElement ?: return null
        if (name.node.elementType != ScadTypes.IDENTIFIER) return null
        return ScadReference(this, TextRange.from(name.startOffsetInParent, name.textLength), ScadReference.Target.MODULE)
    }
}

class ScadReferenceExpression(node: ASTNode) : ScadPsiElement(node) {
    val identifier: PsiElement get() = firstChild

    /** Steht vor `(…)`: Funktionsaufruf (oder Aufruf eines Funktionsliterals in einer Variablen). */
    val isCallee: Boolean get() = (parent as? ScadCallExpression)?.callee == this

    override fun getReference(): PsiReference = ScadReference(
        this,
        TextRange(0, textLength),
        if (isCallee) ScadReference.Target.FUNCTION else ScadReference.Target.VARIABLE,
    )
}

class ScadCallExpression(node: ASTNode) : ScadPsiElement(node) {
    val callee: PsiElement? get() = firstChild
    val argumentList: PsiElement? get() = childOfType(ScadTypes.ARGUMENT_LIST)
}

/** `include <…>` oder `use <…>` */
class ScadImportStatement(node: ASTNode) : ScadPsiElement(node) {
    val isInclude: Boolean get() = node.elementType == ScadTypes.INCLUDE_STATEMENT
    private val pathToken: PsiElement? get() = childOfType(ScadTypes.IMPORT_PATH)

    /** Pfad ohne spitze Klammern */
    val path: String? get() = pathToken?.text?.removePrefix("<")?.removeSuffix(">")?.trim()

    /** Bereich des Pfads (ohne Klammern) relativ zu diesem Element */
    val pathRange: TextRange?
        get() {
            val token = pathToken ?: return null
            val text = token.text
            val start = token.startOffsetInParent + 1
            val end = token.startOffsetInParent + text.length - if (text.endsWith(">")) 1 else 0
            return if (end >= start) TextRange(start, end) else null
        }

    override fun getReferences(): Array<PsiReference> = ScadImports.fileReferences(this)
}

private fun shorten(text: String): String {
    val oneLine = text.replace(Regex("\\s+"), " ")
    return if (oneLine.length > 40) oneLine.take(37) + "…" else oneLine
}

object ScadElementFactory {
    private fun file(project: Project, text: String): ScadFile =
        PsiFileFactory.getInstance(project).createFileFromText("dummy.scad", ScadFileType, text) as ScadFile

    fun identifier(project: Project, name: String): PsiElement =
        (file(project, "$name = 0;").firstChild as ScadAssignment).nameIdentifier!!
}

object ScadPsiFactory {
    fun create(node: ASTNode): PsiElement = when (node.elementType) {
        ScadTypes.MODULE_DECLARATION -> ScadModuleDeclaration(node)
        ScadTypes.FUNCTION_DECLARATION -> ScadFunctionDeclaration(node)
        ScadTypes.ASSIGNMENT -> ScadAssignment(node)
        ScadTypes.PARAMETER -> ScadParameter(node)
        ScadTypes.ARGUMENT -> ScadArgument(node)
        ScadTypes.MODULE_CALL -> ScadModuleCall(node)
        ScadTypes.REFERENCE_EXPRESSION -> ScadReferenceExpression(node)
        ScadTypes.CALL_EXPRESSION -> ScadCallExpression(node)
        ScadTypes.INCLUDE_STATEMENT, ScadTypes.USE_STATEMENT -> ScadImportStatement(node)
        else -> ScadPsiElement(node)
    }
}
