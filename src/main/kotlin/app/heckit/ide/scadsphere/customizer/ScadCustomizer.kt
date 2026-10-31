package app.heckit.ide.scadsphere.customizer

import app.heckit.ide.scadsphere.ScadFileStates
import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.lang.ScadTypes
import app.heckit.ide.scadsphere.psi.ScadAssignment
import app.heckit.ide.scadsphere.psi.ScadFile
import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import java.math.BigDecimal
import java.math.RoundingMode

enum class ParameterType { NUMBER, STRING, BOOLEAN, VECTOR }

/** Wert eines Parameters; [toScad] als OpenSCAD-Ausdruck (für `-D` und den Code), [toSetValue] wie in OpenSCADs .json. */
sealed interface ParameterValue {
    fun toScad(): String
    fun toSetValue(): String = toScad()

    data class Number(val value: Double) : ParameterValue {
        override fun toScad() = formatNumber(value)
    }

    data class Text(val value: String) : ParameterValue {
        override fun toScad() = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
        override fun toSetValue() = value
    }

    data class Bool(val value: Boolean) : ParameterValue {
        override fun toScad() = value.toString()
    }

    data class Vector(val values: List<Double>) : ParameterValue {
        override fun toScad() = values.joinToString(", ", "[", "]") { formatNumber(it) }
    }
}

data class ParameterOption(val value: ParameterValue, val label: String)

/**
 * Ein Parameter des Customizers, nach den Regeln von OpenSCAD: Zuweisung auf oberster Ebene mit festem Wert (Zahl,
 * Text, true/false, Vektor mit bis zu 4 Zahlen) vor der ersten Zeile mit `{`. Gruppe aus `/* [Name] */`, Beschreibung
 * aus der `//`-Zeile direkt darüber, Art des Eingabefelds aus dem Kommentar dahinter (`// [0:5:100]`, `// [a, b]`).
 */
data class CustomizerParameter(
    val name: String,
    val group: String,
    val description: String?,
    val type: ParameterType,
    val initial: ParameterValue,
    val options: List<ParameterOption>? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val maxLength: Int? = null,
) {
    val isSlider: Boolean get() = min != null && max != null && options == null

    /** Wert aus einem gespeicherten OpenSCAD-Ausdruck, null wenn er nicht (mehr) zum Typ passt. */
    fun parse(expression: String): ParameterValue? = ScadCustomizer.parseScad(expression.trim(), type)
}

/** Parameter mit der Stelle seines Werts im Code (zum Zurückschreiben). */
class ParsedParameter(val parameter: CustomizerParameter, val valueRange: TextRange)

object ScadCustomizer {
    const val DEFAULT_GROUP = "Parameters"
    private const val HIDDEN_GROUP = "Hidden"
    private val GROUP = Regex("""^/\*\s*\[([^\]]*)]\s*\*/$""")

    fun parameters(file: ScadFile): List<ParsedParameter> {
        val text = file.text
        val stopLine = stopLine(file, text)
        val result = mutableListOf<ParsedParameter>()
        var group = DEFAULT_GROUP
        for (child in file.children) {
            if (child is PsiComment) {
                GROUP.find(child.text.trim())?.let { group = it.groupValues[1].trim() }
                continue
            }
            val assignment = child as? ScadAssignment ?: continue
            val line = StringUtil.offsetToLineNumber(text, assignment.textRange.startOffset)
            if (line >= stopLine) break
            if (group == HIDDEN_GROUP) continue
            val name = assignment.name ?: continue
            val valueElement = assignment.value ?: continue
            val value = literal(valueElement) ?: continue
            val parameter = annotate(name, group, description(text, line), value, trailingComment(assignment)) ?: continue
            result += ParsedParameter(parameter, valueElement.textRange)
        }
        return result
    }

    /**
     * OpenSCAD hört bei der ersten Zeile mit `{` auf (Kommentare zählen nicht, Text in Anführungszeichen schon).
     */
    private fun stopLine(file: ScadFile, text: String): Int {
        var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
        while (leaf != null) {
            val type = leaf.node.elementType
            if (type == ScadTypes.LBRACE || (type == ScadTypes.STRING && '{' in leaf.text)) {
                return StringUtil.offsetToLineNumber(text, leaf.textRange.startOffset)
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return Int.MAX_VALUE
    }

    /** `//`-Kommentar in der Zeile direkt über der Zuweisung. */
    private fun description(text: String, line: Int): String? {
        if (line == 0) return null
        val start = StringUtil.lineColToOffset(text, line - 1, 0)
        val end = StringUtil.lineColToOffset(text, line, 0)
        if (start < 0 || end < 0) return null
        val previous = text.substring(start, end).trim()
        if (!previous.startsWith("//")) return null
        return previous.removePrefix("//").trim().ifEmpty { null }
    }

    /** `//`-Kommentar hinter der Zuweisung in derselben Zeile. */
    private fun trailingComment(assignment: ScadAssignment): String? {
        var next: PsiElement? = assignment.nextSibling
        while (next is PsiWhiteSpace && '\n' !in next.text) next = next.nextSibling
        if (next !is PsiComment || next.node.elementType != ScadTypes.LINE_COMMENT) return null
        return next.text.removePrefix("//").trim()
    }

    private fun literal(element: PsiElement): ParameterValue? = when (element.node.elementType) {
        ScadTypes.LITERAL_EXPRESSION -> when (element.firstChild?.node?.elementType) {
            ScadTypes.NUMBER -> parseNumber(element.text)?.let { ParameterValue.Number(it) }
            ScadTypes.STRING -> ParameterValue.Text(unquote(element.text))
            ScadTypes.TRUE -> ParameterValue.Bool(true)
            ScadTypes.FALSE -> ParameterValue.Bool(false)
            else -> null
        }
        ScadTypes.UNARY_EXPRESSION -> signedNumber(element)?.let { ParameterValue.Number(it) }
        ScadTypes.VECTOR_EXPRESSION -> {
            val items = element.children.toList()
            val numbers = items.map { item -> if (item.node.elementType == ScadTypes.UNARY_EXPRESSION) signedNumber(item) else numberLiteral(item) }
            if (items.isEmpty() || items.size > 4 || numbers.any { it == null }) null else ParameterValue.Vector(numbers.map { it!! })
        }
        else -> null
    }

    private fun numberLiteral(element: PsiElement): Double? =
        if (element.node.elementType == ScadTypes.LITERAL_EXPRESSION && element.firstChild?.node?.elementType == ScadTypes.NUMBER) parseNumber(element.text) else null

    /** `-5` oder `+3` */
    private fun signedNumber(element: PsiElement): Double? {
        val sign = element.firstChild?.node?.elementType
        if (sign != ScadTypes.MINUS && sign != ScadTypes.PLUS) return null
        val operand = element.children.singleOrNull() ?: return null
        val number = numberLiteral(operand) ?: return null
        return if (sign == ScadTypes.MINUS) -number else number
    }

    private fun parseNumber(text: String): Double? =
        if (text.startsWith("0x") || text.startsWith("0X")) text.substring(2).toLongOrNull(16)?.toDouble() else text.toDoubleOrNull()

    private fun unquote(text: String): String {
        val inner = text.removePrefix("\"").removeSuffix("\"")
        val out = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val n = inner[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    else -> out.append(n)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Art des Eingabefelds aus dem Kommentar hinter dem Wert. */
    private fun annotate(name: String, group: String, description: String?, value: ParameterValue, comment: String?): CustomizerParameter? {
        val base = when (value) {
            is ParameterValue.Number -> CustomizerParameter(name, group, description, ParameterType.NUMBER, value)
            is ParameterValue.Text -> CustomizerParameter(name, group, description, ParameterType.STRING, value)
            is ParameterValue.Bool -> CustomizerParameter(name, group, description, ParameterType.BOOLEAN, value)
            is ParameterValue.Vector -> CustomizerParameter(name, group, description, ParameterType.VECTOR, value)
        }
        if (comment == null || value is ParameterValue.Bool) return base
        if (value is ParameterValue.Text && !comment.startsWith("[")) {
            return comment.toIntOrNull()?.let { base.copy(maxLength = it) } ?: base
        }
        if (!comment.startsWith("[")) return base
        val inner = comment.substring(1, comment.indexOf(']').takeIf { it > 0 } ?: comment.length).trim()
        if (inner.isEmpty()) return base
        val items = splitOptions(inner)
        // [max], [min:max], [min:step:max] für Zahlen und Vektoren
        if (items.size == 1 && (value is ParameterValue.Number || value is ParameterValue.Vector)) {
            val parts = inner.split(':').map { it.trim().toDoubleOrNull() }
            if (parts.none { it == null }) {
                return when (parts.size) {
                    1 -> base.copy(min = 0.0, max = parts[0], step = 1.0)
                    2 -> base.copy(min = parts[0], max = parts[1], step = 1.0)
                    3 -> base.copy(min = parts[0], step = parts[1], max = parts[2])
                    else -> base
                }
            }
        }
        if (value is ParameterValue.Vector) return base
        // Auswahlliste: [a, b, c] oder [wert:Beschriftung, …]
        val options = items.mapNotNull { item ->
            val colon = colonOutsideQuotes(item)
            val rawValue = (if (colon >= 0) item.substring(0, colon) else item).trim()
            val label = (if (colon >= 0) item.substring(colon + 1) else rawValue).trim().let(::stripQuotes)
            val optionValue: ParameterValue = when (value) {
                is ParameterValue.Number -> ParameterValue.Number(rawValue.toDoubleOrNull() ?: return@mapNotNull null)
                else -> ParameterValue.Text(stripQuotes(rawValue))
            }
            ParameterOption(optionValue, label)
        }
        return if (options.isEmpty()) base else base.copy(options = options)
    }

    /** Teilt an Kommas, aber nicht innerhalb von Anführungszeichen. */
    private fun splitOptions(text: String): List<String> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in text) {
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == ',' && !quoted -> { items += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        items += current.toString().trim()
        return items.filter { it.isNotEmpty() }
    }

    private fun colonOutsideQuotes(text: String): Int {
        var quoted = false
        text.forEachIndexed { index, c ->
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) return index
        }
        return -1
    }

    private fun stripQuotes(text: String) = if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text.substring(1, text.length - 1) else text

    /** Gespeicherter OpenSCAD-Ausdruck (aus [ParameterValue.toScad]) als Wert des Typs. */
    fun parseScad(expression: String, type: ParameterType): ParameterValue? = when (type) {
        ParameterType.NUMBER -> expression.toDoubleOrNull()?.let { ParameterValue.Number(it) }
        ParameterType.BOOLEAN -> expression.toBooleanStrictOrNull()?.let { ParameterValue.Bool(it) }
        ParameterType.STRING -> if (expression.length >= 2 && expression.startsWith('"') && expression.endsWith('"')) ParameterValue.Text(unquote(expression)) else null
        ParameterType.VECTOR -> parseVector(expression)
    }

    /** Wert aus OpenSCADs Parametersatz (.json): dort stehen alle Werte als Text, neuere Versionen schreiben auch Zahlen. */
    fun parseSetValue(element: JsonElement, type: ParameterType): ParameterValue? = runCatching {
        when {
            element.isJsonArray -> if (type == ParameterType.VECTOR) ParameterValue.Vector(element.asJsonArray.map { it.asDouble }) else null
            !element.isJsonPrimitive -> null
            type == ParameterType.STRING -> ParameterValue.Text(element.asString)
            type == ParameterType.BOOLEAN -> element.asString.toBooleanStrictOrNull()?.let { ParameterValue.Bool(it) }
            type == ParameterType.NUMBER -> element.asString.trim().toDoubleOrNull()?.let { ParameterValue.Number(it) }
            else -> parseVector(element.asString)
        }
    }.getOrNull()

    private fun parseVector(text: String): ParameterValue.Vector? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return null
        val numbers = inner.split(',').map { it.trim().toDoubleOrNull() ?: return null }
        return ParameterValue.Vector(numbers)
    }

    /** Geänderte Werte ([overrides]) in den Code schreiben, als ein Schritt zum Rückgängigmachen. Auf dem EDT. */
    fun writeToCode(project: Project, file: VirtualFile, overrides: Map<String, String>): Boolean {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val documents = PsiDocumentManager.getInstance(project)
        documents.commitDocument(document)
        val psi = documents.getPsiFile(document) as? ScadFile ?: return false
        val replacements = parameters(psi).mapNotNull { parsed ->
            val value = overrides[parsed.parameter.name]?.let(parsed.parameter::parse) ?: return@mapNotNull null
            parsed.valueRange to value.toScad()
        }.sortedByDescending { it.first.startOffset }
        WriteCommandAction.runWriteCommandAction(project, ScadSphereBundle.message("customizer.writeToCode"), null, {
            for ((range, text) in replacements) document.replaceString(range.startOffset, range.endOffset, text)
        }, psi)
        return true
    }

    /**
     * Geänderte Werte einer Datei als `name=ausdruck` für `-D`, nur für Parameter, die es im Code noch gibt und deren
     * Typ passt. Liest PSI - im Lese-Zugriff oder auf dem EDT aufrufen.
     */
    fun defines(project: Project, file: VirtualFile): List<String> {
        val overrides = ScadFileStates.of(project).overrides(file)
        if (overrides.isEmpty()) return emptyList()
        return ApplicationManager.getApplication().runReadAction(Computable {
            val psi = PsiManager.getInstance(project).findFile(file) as? ScadFile ?: return@Computable emptyList()
            val parameters = parameters(psi).associateBy { it.parameter.name }
            overrides.mapNotNull { (name, expression) ->
                val value = parameters[name]?.parameter?.parse(expression) ?: return@mapNotNull null
                "$name=${value.toScad()}"
            }
        })
    }
}

/** Zahl ohne überflüssige Nachkommastellen und ohne Exponent: `80`, `1.25`, `-0.5`. */
fun formatNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val text = BigDecimal.valueOf(value).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}
