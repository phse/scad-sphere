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
            val (_, missing) = missingArguments(element) ?: return
            val nameElement = callNameElement(element) ?: return
            holder.registerProblem(nameElement, ScadSphereBundle.message("inspection.missingArgument", missing.joinToString(", ") { "'$it'" }, missing.size))
        }
    }
}

/** Name des Aufgerufenen bei einem Modul- oder Funktionsaufruf. */
fun callNameElement(element: PsiElement): PsiElement? = when (element) {
    is ScadModuleCall -> element.nameElement
    is ScadCallExpression -> element.callee as? ScadReferenceExpression
    else -> null
}

/**
 * Aufgerufene Deklaration und die Parameter ohne Standardwert, für die [element] (Modul- oder Funktionsaufruf) kein
 * Argument mitgibt; null, wenn es kein Aufruf ist oder nichts fehlt.
 */
fun missingArguments(element: PsiElement): Pair<ScadCallableDeclaration, List<String>>? {
    val (reference, arguments) = when (element) {
        is ScadModuleCall -> (element.reference as? ScadReference) to element.arguments
        is ScadCallExpression -> {
            val callee = element.callee as? ScadReferenceExpression ?: return null
            (callee.reference as? ScadReference) to element.argumentList?.children?.filterIsInstance<ScadArgument>().orEmpty()
        }
        else -> return null
    }
    if (reference == null) return null
    val declaration = reference.multiResolve(false).mapNotNull { it.element as? ScadCallableDeclaration }.distinct().singleOrNull() ?: return null
    val positional = arguments.count { it.argumentName == null }
    val named = arguments.mapNotNull { it.argumentName?.text }.toSet()
    val missing = declaration.parameters.withIndex()
        .filter { (index, parameter) -> parameter.defaultValue == null && !isExempt(parameter.name) && index >= positional && parameter.name !in named }
        .map { it.value.name!! }
    return if (missing.isEmpty()) null else declaration to missing
}

/**
 * Parameter, die bei [element] (Modul- oder Funktionsaufruf) zwar ein Argument bekommen, aber sicher `undef`:
 * das Literal `undef` oder eine Variable, die es nirgends gibt (z. B. Tippfehler `undefined`).
 */
fun undefinedArguments(element: PsiElement): Pair<ScadCallableDeclaration, List<String>>? {
    val (reference, arguments) = when (element) {
        is ScadModuleCall -> (element.reference as? ScadReference) to element.arguments
        is ScadCallExpression -> {
            val callee = element.callee as? ScadReferenceExpression ?: return null
            (callee.reference as? ScadReference) to element.argumentList?.children?.filterIsInstance<ScadArgument>().orEmpty()
        }
        else -> return null
    }
    if (reference == null) return null
    val declaration = reference.multiResolve(false).mapNotNull { it.element as? ScadCallableDeclaration }.distinct().singleOrNull() ?: return null
    val names = declaration.parameters.map { it.name }
    var position = 0
    val undefined = arguments.mapNotNull { argument ->
        val name = argument.argumentName?.text ?: names.getOrNull(position++)
        val value = argument.value
        val isUndef = value?.text == "undef" ||
            (value is ScadReferenceExpression && !value.text.startsWith("$") && (value.reference as? ScadReference)?.multiResolve(false).isNullOrEmpty())
        name?.takeIf { isUndef }
    }
    return if (undefined.isEmpty()) null else declaration to undefined
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

/**
 * Was OpenSCAD beim Rechnen als `DEPRECATED` meldet oder in neuen Versionen gar nicht mehr kennt (2026.04: `assign`,
 * `import_stl`, `dxf_linear_extrude` sind „unknown module“), schon beim Tippen: alte Module und alte Namen von
 * Argumenten (`filename`, `triangles`). Ein eigenes Modul gleichen Namens zählt nicht.
 */
object ScadDeprecated {
    /** Modul → Ersatz (null: kein einfacher Ersatz, die Meldung erklärt den Weg) */
    val MODULES: Map<String, String?> = mapOf(
        "assign" to "let",
        "child" to "children",
        "import_stl" to "import",
        "import_off" to "import",
        "import_dxf" to "import",
        "dxf_linear_extrude" to null,
        "dxf_rotate_extrude" to null,
    )

    /** Modul → (Argument → Ersatz bzw. null) */
    val ARGUMENTS: Map<String, Map<String, String?>> = mapOf(
        "import" to mapOf("filename" to "file"),
        "polyhedron" to mapOf("triangles" to "faces"),
        "linear_extrude" to mapOf("file" to null, "layer" to null),
        "rotate_extrude" to mapOf("file" to null, "layer" to null),
    )
}

class ScadDeprecatedInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            if (element !is ScadModuleCall) return
            val name = element.nameElement?.takeIf { it.node.elementType == ScadTypes.IDENTIFIER } ?: return
            val module = name.text
            if (module !in ScadDeprecated.MODULES && module !in ScadDeprecated.ARGUMENTS) return
            if ((element.reference as? ScadReference)?.multiResolve(false).orEmpty().isNotEmpty()) return
            if (module in ScadDeprecated.MODULES) {
                val replacement = ScadDeprecated.MODULES[module]
                val message = when {
                    replacement != null -> ScadSphereBundle.message("inspection.deprecated.module", module, replacement)
                    else -> ScadSphereBundle.message("inspection.deprecated.extrude", module, module.removePrefix("dxf_"))
                }
                if (replacement == null) holder.registerProblem(name, message, ProblemHighlightType.LIKE_DEPRECATED)
                else holder.registerProblem(name, message, ProblemHighlightType.LIKE_DEPRECATED, RenameDeprecatedFix(replacement))
            }
            val arguments = ScadDeprecated.ARGUMENTS[module] ?: return
            for (argument in element.arguments) {
                val argumentName = argument.argumentName ?: continue
                if (argumentName.text !in arguments) continue
                val replacement = arguments[argumentName.text]
                if (replacement == null) {
                    holder.registerProblem(argumentName, ScadSphereBundle.message("inspection.deprecated.extrudeFile", module, argumentName.text), ProblemHighlightType.LIKE_DEPRECATED)
                } else {
                    holder.registerProblem(argumentName, ScadSphereBundle.message("inspection.deprecated.argument", argumentName.text, replacement),
                        ProblemHighlightType.LIKE_DEPRECATED, RenameDeprecatedFix(replacement))
                }
            }
        }
    }
}

/** Alten Namen (Modul oder Argument) durch [replacement] ersetzen; `child()` ohne Argument meinte das erste Kind. */
class RenameDeprecatedFix(private val replacement: String) : LocalQuickFix {
    override fun getFamilyName(): String = ScadSphereBundle.message("fix.replaceDeprecated", replacement)

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val name = descriptor.psiElement ?: return
        val call = name.parent as? ScadModuleCall
        val document = PsiDocumentManager.getInstance(project).getDocument(name.containingFile) ?: return
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
        val childWithoutIndex = call != null && call.nameElement == name && name.text == "child" && call.arguments.isEmpty()
        val open = call?.argumentList?.textRange?.startOffset
        // Von hinten nach vorn, damit die Stellen gültig bleiben
        if (childWithoutIndex && open != null && document.charsSequence[open] == '(') document.insertString(open + 1, "0")
        document.replaceString(name.textRange.startOffset, name.textRange.endOffset, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
