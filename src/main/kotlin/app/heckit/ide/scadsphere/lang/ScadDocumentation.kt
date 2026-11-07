package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadFunctionDeclaration
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import app.heckit.ide.scadsphere.psi.ScadParameter
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace

/**
 * Dokumentation (F1/Strg+J, beim Darüberfahren): eingebaute Module und Funktionen mit Beschreibung und Link ins
 * Handbuch, eigene Deklarationen mit Signatur und dem Kommentar darüber (oder dahinter in derselben Zeile).
 */
class ScadDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        val leaf = contextElement ?: return null
        if (leaf.node?.elementType != ScadTypes.IDENTIFIER) return null
        return if (builtinAt(leaf) != null) leaf else null
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null
        builtinAt(element)?.let { return builtinDoc(it) }
        if (element is ScadNamedElement) return declarationDoc(element)
        return null
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? ScadNamedElement)?.let { StringUtil.escapeXmlEntities(it.signature()) }

    /** Eingebautes Modul/Funktion/Variable an einem nicht aufgelösten Namen. */
    private fun builtinAt(leaf: PsiElement): ScadBuiltins.Builtin? {
        if (leaf.node?.elementType != ScadTypes.IDENTIFIER) return null
        val name = leaf.text
        return when (val parent = leaf.parent) {
            is ScadModuleCall -> {
                if (parent.nameElement != leaf || (parent.reference as? ScadReference)?.multiResolve(false)?.isNotEmpty() == true) null
                else ScadBuiltins.module(name)
            }
            is ScadReferenceExpression -> {
                if ((parent.reference as ScadReference).multiResolve(false).isNotEmpty()) null
                else if (parent.isCallee) ScadBuiltins.function(name) else ScadBuiltins.variable(name)
            }
            is ScadParameter, is ScadAssignment -> null
            else -> if (name.startsWith("$")) ScadBuiltins.variable(name) else null
        }
    }

    private fun builtinDoc(builtin: ScadBuiltins.Builtin): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(StringUtil.escapeXmlEntities(builtin.signature))
        append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append(builtin.description)
        append(DocumentationMarkup.CONTENT_END)
        append(DocumentationMarkup.SECTIONS_START)
        if (builtin.experimental) section(ScadSphereBundle.message("doc.experimental.label"), ScadSphereBundle.message("doc.experimental"))
        section(ScadSphereBundle.message("doc.manual.label"), "<a href=\"${builtin.url}\">OpenSCAD User Manual</a>")
        append(DocumentationMarkup.SECTIONS_END)
    }

    private fun declarationDoc(element: ScadNamedElement): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        val keyword = when (element) {
            is ScadModuleDeclaration -> "module "
            is ScadFunctionDeclaration -> "function "
            else -> ""
        }
        append(StringUtil.escapeXmlEntities(keyword + element.signature()))
        append(DocumentationMarkup.DEFINITION_END)
        val doc = ScadDocComments.of(element)
        val esc = { text: String -> StringUtil.escapeXmlEntities(text) }
        val paragraphs = listOfNotNull(doc?.text, doc?.synopsis, doc?.description)
        if (paragraphs.isNotEmpty() || doc?.usage?.isNotEmpty() == true) {
            append(DocumentationMarkup.CONTENT_START)
            append(paragraphs.joinToString("<p>") { esc(it).replace("\n\n", "<p>").replace("\n", "<br>") })
            if (doc?.usage?.isNotEmpty() == true) append("<pre>").append(doc.usage.joinToString("\n") { esc(it) }).append("</pre>")
            append(DocumentationMarkup.CONTENT_END)
        }
        append(DocumentationMarkup.SECTIONS_START)
        doc?.arguments?.forEach { (name, text) -> section(esc(name), esc(text)) }
        doc?.seeAlso?.let { section(ScadSphereBundle.message("doc.seeAlso.label"), esc(it)) }
        element.containingFile?.name?.let { section(ScadSphereBundle.message("doc.file.label"), StringUtil.escapeXmlEntities(it)) }
        append(DocumentationMarkup.SECTIONS_END)
    }

    private fun StringBuilder.section(label: String, value: String) {
        append(DocumentationMarkup.SECTION_HEADER_START).append(label).append(DocumentationMarkup.SECTION_SEPARATOR)
        append(value).append(DocumentationMarkup.SECTION_END)
    }
}
