package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.psi.ScadNamedElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Doku-Kommentare zu Deklarationen: der Kommentar direkt darüber oder dahinter (auch `//!` wie in NopSCADlib) und
 * die Blöcke von BOSL2 (`// Module: cuboid()` mit Synopsis, Usage, Description, Arguments …), die dort mit
 * Leerzeile oder ganz woanders in der Datei stehen.
 */
object ScadDocComments {
    /** [text]: einfacher Kommentar; bei BOSL2-Blöcken die Abschnitte einzeln. */
    class Doc(
        val text: String? = null,
        val synopsis: String? = null,
        val usage: List<String> = emptyList(),
        val description: String? = null,
        val arguments: List<Pair<String, String>> = emptyList(),
        val seeAlso: String? = null,
    ) {
        /** Eine Zeile für die Vervollständigung. */
        val summary: String?
            get() = (synopsis ?: text?.lineSequence()?.firstOrNull { it.isNotBlank() })?.trim()?.let {
                if (it.length > SUMMARY_LENGTH) it.take(SUMMARY_LENGTH - 1).trimEnd() + "…" else it
            }
    }

    fun of(element: ScadNamedElement): Doc? {
        plain(element)?.let { return Doc(text = it) }
        val name = element.name ?: return null
        return element.containingFile?.let { blocks(it)[name] }
    }

    /** Kommentarzeilen direkt über der Deklaration, sonst ein Kommentar dahinter in der ersten Zeile. */
    private fun plain(element: ScadNamedElement): String? {
        val lines = mutableListOf<String>()
        var sibling = element.prevSibling
        while (sibling != null) {
            if (sibling is PsiWhiteSpace) {
                if (sibling.text.count { it == '\n' } > 1) break
            } else if (sibling is PsiComment) {
                lines.add(0, clean(sibling.text))
            } else {
                break
            }
            sibling = sibling.prevSibling
        }
        if (lines.isEmpty()) {
            var next = element.nextSibling
            while (next is PsiWhiteSpace && !next.text.contains('\n')) next = next.nextSibling
            if (next is PsiComment && next.node.elementType == ScadTypes.LINE_COMMENT) lines += clean(next.text)
        }
        if (lines.isEmpty()) {
            // NopSCADlib: `module name(a, b) //! Beschreibung` - der Kommentar liegt in der Deklaration vor dem Rumpf
            val firstLineEnd = element.text.indexOf('\n').let { if (it < 0) element.textLength else it }
            PsiTreeUtil.findChildrenOfType(element, PsiComment::class.java)
                .firstOrNull { it.textRange.startOffset - element.textRange.startOffset < firstLineEnd && it.text.startsWith("//!") }
                ?.let { lines += clean(it.text) }
        }
        return lines.joinToString("\n").trim().ifEmpty { null }
    }

    private fun clean(comment: String): String = when {
        comment.startsWith("//") -> comment.removePrefix("//").removePrefix("!").trim()
        else -> comment.removePrefix("/**").removePrefix("/*").removeSuffix("*/").lines()
            .joinToString("\n") { it.trim().removePrefix("*").trim() }.trim()
    }

    /** BOSL2-Blöcke einer Datei nach Name, gemerkt bis zur nächsten Änderung. */
    fun blocks(file: PsiFile): Map<String, Doc> = CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(parseBlocks(file.text), file)
    }

    fun parseBlocks(text: String): Map<String, Doc> {
        if (!text.contains("// Module:") && !text.contains("// Function") && !text.contains("// Constant:")) return emptyMap()
        val result = HashMap<String, Doc>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val header = HEADER.find(lines[i])
            if (header == null) { i++; continue }
            val names = NAME.findAll(header.groupValues[2]).map { it.value }.toList()
            val sections = LinkedHashMap<String, MutableList<String>>()
            var current: MutableList<String>? = null
            i++
            while (i < lines.size && lines[i].startsWith("//")) {
                val line = lines[i].removePrefix("//")
                val key = KEY.find(line)
                if (key != null && !line.startsWith("  ")) {
                    val name = key.groupValues[1].trim()
                    current = sections.getOrPut(name) { mutableListOf() }
                    // Bei Usage steht hinter dem Doppelpunkt nur eine Überschrift („Usage: Standard Cubes“)
                    if (name != "Usage") key.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { current.add(it) }
                } else if (line.isBlank()) {
                    current?.add("")
                } else {
                    current?.add(line.trim())
                }
                i++
            }
            fun joined(key: String) = sections[key]?.joinToString("\n")?.trim()?.ifEmpty { null }
            val doc = Doc(
                synopsis = joined("Synopsis"),
                usage = sections.filterKeys { it == "Usage" }.values.flatten().filter { it.isNotBlank() },
                // Fließtext: umbrochene Zeilen zusammenfügen, Leerzeilen trennen Absätze
                description = joined("Description")?.split(Regex("\n\\s*\n"))?.joinToString("\n\n") { it.replace('\n', ' ') },
                arguments = sections["Arguments"].orEmpty().mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0 || line.startsWith("---")) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
                },
                seeAlso = joined("See Also"),
            )
            for (name in names) result.putIfAbsent(name, doc)
        }
        return result
    }

    private const val SUMMARY_LENGTH = 60
    private val HEADER = Regex("""^// (Module|Function|Function&Module|Constant): (.+)$""")
    private val NAME = Regex("""[A-Za-z_$][A-Za-z0-9_]*""")
    private val KEY = Regex("""^ ?([A-Z][A-Za-z&]*(?: [A-Z][A-Za-z&]*)?):(.*)$""")
}
