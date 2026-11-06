package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadArgument
import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadCallExpression
import app.heckit.ide.scadsphere.psi.ScadCallableDeclaration
import app.heckit.ide.scadsphere.psi.ScadFile
import app.heckit.ide.scadsphere.psi.ScadImportStatement
import app.heckit.ide.scadsphere.psi.ScadImports
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import app.heckit.ide.scadsphere.psi.ScadNamedElement
import app.heckit.ide.scadsphere.psi.ScadParameter
import app.heckit.ide.scadsphere.psi.ScadReference
import app.heckit.ide.scadsphere.psi.ScadReferenceExpression
import app.heckit.ide.scadsphere.psi.ScadResolver
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

private fun isUsedIn(declaration: ScadNamedElement, scope: PsiElement): Boolean =
    ReferencesSearch.search(declaration, LocalSearchScope(scope)).findFirst() != null

/** Name, der absichtlich nicht benutzt wird bzw. still weitergereicht wird (`$fn`, `_`). */
private fun isExempt(name: String?): Boolean = name == null || name.startsWith("$") || name.startsWith("_")

/**
 * Nicht benutzte Parameter von Modulen und Funktionen, lokale Variablen (in Blöcken) und let-Variablen. Variablen
 * auf oberster Ebene bleiben außen vor (Customizer, `-D`, include), ebenso Laufvariablen von `for` (Wiederholung).
 */
class ScadUnusedInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            when (element) {
                is ScadParameter -> {
                    val owner = element.parent?.parent ?: return
                    if (owner !is ScadCallableDeclaration && owner.node.elementType != ScadTypes.FUNCTION_LITERAL) return
                    if (isExempt(element.name) || isUsedIn(element, owner)) return
                    report(element, ScadSphereBundle.message("inspection.unused.parameter", element.name!!), null)
                }
                is ScadAssignment -> {
                    val block = element.parent ?: return
                    if (block.node.elementType != ScadTypes.BLOCK || isExempt(element.name) || isUsedIn(element, block)) return
                    report(element, ScadSphereBundle.message("inspection.unused.variable", element.name!!), RemoveElementFix(ScadSphereBundle.message("fix.removeVariable")))
                }
                is ScadArgument -> {
                    if (!element.isDeclaration || isExempt(element.name)) return
                    val owner = element.parent?.parent ?: return
                    val isFor = (owner as? ScadModuleCall)?.moduleName.let { it == "for" || it == "intersection_for" } || owner.node.elementType == ScadTypes.LC_FOR
                    if (isFor || isUsedIn(element, owner)) return
                    report(element, ScadSphereBundle.message("inspection.unused.variable", element.name!!), null)
                }
            }
        }

        private fun report(element: ScadNamedElement, message: String, fix: LocalQuickFix?) {
            val name = element.nameIdentifier ?: return
            if (fix == null) holder.registerProblem(name, message, ProblemHighlightType.LIKE_UNUSED_SYMBOL)
            else holder.registerProblem(name, message, ProblemHighlightType.LIKE_UNUSED_SYMBOL, fix)
        }
    }
}

/** Dieselbe Variable zweimal im selben Bereich: OpenSCAD nimmt überall den letzten Wert, auch vor der zweiten Zuweisung. */
class ScadDuplicateAssignmentInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is ScadFile && element.node?.elementType != ScadTypes.BLOCK) return
            val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
            element.children.filterIsInstance<ScadAssignment>().groupBy { it.name }.forEach { (name, list) ->
                if (name == null || list.size < 2) return@forEach
                val last = list.last()
                val line = document?.getLineNumber(last.textOffset)?.plus(1) ?: 0
                for (assignment in list.dropLast(1)) {
                    val id = assignment.nameIdentifier ?: continue
                    holder.registerProblem(id, ScadSphereBundle.message("inspection.duplicate", name, line), RemoveElementFix(ScadSphereBundle.message("fix.removeAssignment")))
                }
            }
        }
    }
}

/** `use <…>`, aus dem kein Modul und keine Funktion benutzt wird. */
class ScadUnusedUseInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is ScadImportStatement || element.isInclude) return
            val target = ScadImports.importedFile(element) ?: return
            val provided = mutableSetOf<VirtualFile>()
            collect(target, provided)
            val file = element.containingFile
            val used = PsiTreeUtil.collectElements(file) { it is ScadModuleCall || (it is ScadReferenceExpression && it.isCallee) }.any { call ->
                val reference = call.reference as? ScadReference ?: return@any false
                reference.multiResolve(false).any { result -> result.element?.containingFile?.virtualFile?.let { it in provided } == true }
            }
            if (!used) {
                holder.registerProblem(element, ScadSphereBundle.message("inspection.unusedUse", element.path ?: ""), ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    RemoveElementFix(ScadSphereBundle.message("fix.removeUse")))
            }
        }

        /** Die Datei und alles, was sie per include einbindet (use reicht deren Module und Funktionen mit durch). */
        private fun collect(file: ScadFile, into: MutableSet<VirtualFile>) {
            val virtualFile = file.virtualFile ?: return
            if (!into.add(virtualFile)) return
            file.imports.filter { it.isInclude }.forEach { statement -> ScadImports.importedFile(statement)?.let { collect(it, into) } }
        }
    }
}

/** Aufruf ohne einen Parameter, der keinen Standardwert hat - OpenSCAD rechnet dann still mit `undef`. */
class ScadMissingArgumentInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val (reference, nameElement, arguments) = when (element) {
                is ScadModuleCall -> Triple(element.reference as? ScadReference, element.nameElement, element.arguments)
                is ScadCallExpression -> {
                    val callee = element.callee as? ScadReferenceExpression ?: return
                    Triple(callee.reference as? ScadReference, callee, element.argumentList?.children?.filterIsInstance<ScadArgument>().orEmpty())
                }
                else -> return
            }
            if (reference == null || nameElement == null) return
            val declaration = reference.multiResolve(false).mapNotNull { it.element as? ScadCallableDeclaration }.distinct().singleOrNull() ?: return
            val positional = arguments.count { it.argumentName == null }
            val named = arguments.mapNotNull { it.argumentName?.text }.toSet()
            val missing = declaration.parameters.withIndex()
                .filter { (index, parameter) -> parameter.defaultValue == null && !isExempt(parameter.name) && index >= positional && parameter.name !in named }
                .map { it.value.name!! }
            if (missing.isEmpty()) return
            holder.registerProblem(nameElement, ScadSphereBundle.message("inspection.missingArgument", missing.joinToString(", ") { "'$it'" }, missing.size))
        }
    }
}

/** Element samt Zeilenrest (Leerraum bis einschließlich Zeilenumbruch) entfernen. */
class RemoveElementFix(private val text: String) : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val start = descriptor.psiElement ?: return
        val element = (if (start is ScadImportStatement) start else PsiTreeUtil.getParentOfType(start, ScadAssignment::class.java, false)) ?: return
        removeStatement(element)
    }
}

/** Anweisung entfernen, mit dem Leerraum dahinter bis zum Zeilenende. */
fun removeStatement(element: PsiElement) {
    val next = element.nextSibling
    if (next is PsiWhiteSpace) {
        val text = next.text
        val newline = text.indexOf('\n')
        if (newline >= 0 && text.length > newline + 1) {
            // Einrückung der nächsten Zeile behalten
            val file = element.containingFile
            val document = PsiDocumentManager.getInstance(element.project).getDocument(file)
            if (document != null) {
                PsiDocumentManager.getInstance(element.project).doPostponedOperationsAndUnblockDocument(document)
                val range = TextRange(element.textRange.startOffset, next.textRange.startOffset + newline + 1)
                val lineStart = document.getLineStartOffset(document.getLineNumber(range.startOffset))
                val prefix = document.charsSequence.subSequence(lineStart, range.startOffset)
                val from = if (prefix.isBlank()) lineStart else range.startOffset
                document.deleteString(from, range.endOffset)
                PsiDocumentManager.getInstance(element.project).commitDocument(document)
                return
            }
        }
        element.parent.deleteChildRange(element, next)
        return
    }
    element.delete()
}

internal fun ScadResolver.isLocalDeclaration(element: PsiElement): Boolean = when (element) {
    is ScadParameter -> true
    is ScadArgument -> element.isDeclaration
    is ScadAssignment -> element.parent?.node?.elementType == ScadTypes.BLOCK
    else -> false
}
