package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadCallExpression
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadImportStatement
import app.heckit.ide.scadsphere.psi.ScadImports
import app.heckit.ide.scadsphere.psi.ScadLibraryCatalog
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadFunctionDeclaration
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadModuleDeclaration
import app.heckit.ide.scadsphere.psi.ScadParameter
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement

/** Farben aus der Bedeutung: Deklarationen, Aufrufe eigener und eingebauter Module/Funktionen, Parameter. */
class ScadAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is ScadModuleDeclaration -> element.nameIdentifier?.let { holder.color(it, ScadColors.MODULE_DECLARATION) }
            is ScadFunctionDeclaration -> element.nameIdentifier?.let { holder.color(it, ScadColors.FUNCTION_DECLARATION) }
            is ScadParameter -> element.nameIdentifier?.let { holder.color(it, special(it.text) ?: ScadColors.PARAMETER) }
            is ScadArgument -> element.argumentName?.let { name ->
                val key = special(name.text) ?: if (element.isDeclaration) null else ScadColors.NAMED_ARGUMENT
                key?.let { holder.color(name, it) }
            }
            is ScadModuleCall -> annotateModuleCall(element, holder)
            is ScadReferenceExpression -> annotateReference(element, holder)
            is ScadImportStatement -> annotateImport(element, holder)
        }
    }

    private fun annotateModuleCall(call: ScadModuleCall, holder: AnnotationHolder) {
        var child = call.firstChild
        while (child != null && child.node.elementType in ScadTypes.MODIFIERS) {
            holder.color(child, ScadColors.MODIFIER)
            child = child.nextSibling
        }
        val name = call.nameElement ?: return
        if (name.node.elementType != ScadTypes.IDENTIFIER) return
        val resolved = (call.reference as? ScadReference)?.multiResolve(false).orEmpty().isNotEmpty()
        val key = when {
            resolved -> ScadColors.MODULE_CALL
            ScadBuiltins.module(name.text) != null || name.text in ScadDeprecated.MODULES -> ScadColors.BUILTIN_MODULE
            else -> ScadColors.MODULE_CALL
        }
        holder.color(name, key)
        if (!resolved && key == ScadColors.MODULE_CALL && ScadImports.allImportsFound(call.containingFile as ScadFile)) {
            holder.unknown(name, ScadSphereBundle.message("annotation.unknownModule", name.text),
                CreateDeclarationFix(name.text, true, CreateDeclarationFix.argumentNames(call.arguments)))
        }
    }

    private fun annotateImport(statement: ScadImportStatement, holder: AnnotationHolder) {
        val path = statement.path ?: return
        val range = statement.pathRange ?: return
        if (path.isBlank() || ScadImports.importedFile(statement) != null) return
        var annotation = holder.newAnnotation(HighlightSeverity.WARNING, ScadSphereBundle.message("annotation.fileNotFound", path))
            .range(range.shiftRight(statement.textRange.startOffset))
        // Bekannte Bibliothek (BOSL2, NopSCADlib, …), die noch nicht installiert ist
        ScadLibraryCatalog.forPath(path)?.let { annotation = annotation.withFix(InstallLibraryFix(it)) }
        annotation.create()
    }

    private fun annotateReference(reference: ScadReferenceExpression, holder: AnnotationHolder) {
        val name = reference.text
        special(name)?.let { return holder.color(reference, it) }
        val targets = (reference.reference as ScadReference).multiResolve(false).mapNotNull { it.element }
        val key = when {
            targets.any { it is ScadParameter } -> ScadColors.PARAMETER
            reference.isCallee && targets.any { it is ScadCallableDeclaration } -> ScadColors.FUNCTION_CALL
            reference.isCallee && targets.isEmpty() && ScadBuiltins.function(name) != null -> ScadColors.BUILTIN_FUNCTION
            reference.isCallee -> ScadColors.FUNCTION_CALL
            targets.isEmpty() && name == "PI" -> ScadColors.SPECIAL_VARIABLE
            else -> null
        }
        key?.let { holder.color(reference, it) }
        if (reference.isCallee && targets.isEmpty() && ScadBuiltins.function(name) == null &&
            ScadImports.allImportsFound(reference.containingFile as ScadFile)
        ) {
            val arguments = (reference.parent as? ScadCallExpression)?.argumentList?.children?.filterIsInstance<ScadArgument>().orEmpty()
            holder.unknown(reference, ScadSphereBundle.message("annotation.unknownFunction", name),
                CreateDeclarationFix(name, false, CreateDeclarationFix.argumentNames(arguments)))
        }
    }

    private fun AnnotationHolder.unknown(element: PsiElement, message: String, fix: CreateDeclarationFix) {
        newAnnotation(HighlightSeverity.WEAK_WARNING, message).range(element).withFix(fix).create()
    }

    /** `$fn` & Co. */
    private fun special(name: String): TextAttributesKey? = if (name.startsWith("$")) ScadColors.SPECIAL_VARIABLE else null

    private fun AnnotationHolder.color(element: PsiElement, key: TextAttributesKey) {
        newSilentAnnotation(HighlightSeverity.INFORMATION).range(element).textAttributes(key).create()
    }
}
