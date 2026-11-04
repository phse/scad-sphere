package app.heckit.ide.scadsphere.render

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fehler und Warnungen des letzten Laufs je Vorschau, damit sie auch im Code erscheinen ([ScadRenderAnnotator]).
 * Jede Meldung hängt an einem RangeMarker auf ihrer Zeile und wandert so beim Tippen mit, bis neu gerechnet ist.
 */
@Service(Service.Level.PROJECT)
class RenderMessages(private val project: Project) {
    class Marked(val message: RenderMessage, val marker: RangeMarker)

    /** Quelle (gerechnete Datei) → Meldungen, nach betroffener Datei (kanonischer Pfad) */
    private val bySource = ConcurrentHashMap<String, Map<String, List<Marked>>>()

    /**
     * Meldungen eines Laufs von [source] übernehmen. [stamp]: Änderungsstand des Dokuments beim Start des Laufs -
     * hat sich der Text seitdem geändert, passen die Zeilen nicht mehr, die Meldungen dieser Datei fallen dann weg.
     */
    fun update(source: VirtualFile, messages: List<RenderMessage>, stamp: Long?) {
        val sourcePath = canonical(source.path)
        val marked = messages.asSequence()
            .filter { it.level == RenderMessage.Level.ERROR || it.level == RenderMessage.Level.WARNING }
            .filter { it.file != null && it.line != null }
            .groupBy { canonical(it.file!!) }
            .mapNotNull { (path, list) ->
                val document = (if (path == sourcePath) FileDocumentManager.getInstance().getDocument(source) else document(path))
                    ?: return@mapNotNull null
                if (path == sourcePath && stamp != null && document.modificationStamp != stamp) return@mapNotNull null
                path to list.mapNotNull { mark(document, it) }
            }
            .toMap()
        val old = bySource.put(sourcePath, marked)
        refresh(source, old.orEmpty().keys + marked.keys)
        old?.values?.flatten()?.forEach { it.marker.dispose() }
    }

    fun clear(source: VirtualFile) {
        val old = bySource.remove(canonical(source.path)) ?: return
        refresh(source, old.keys)
        old.values.flatten().forEach { it.marker.dispose() }
    }

    /** Gültige Meldungen zu [path] aus allen Vorschauen */
    fun forFile(path: String): List<Marked> {
        val key = canonical(path)
        return bySource.values.flatMap { it[key].orEmpty() }.filter { it.marker.isValid }
    }

    private fun mark(document: Document, message: RenderMessage): Marked? {
        val line = message.line!! - 1
        if (line !in 0 until document.lineCount) return null
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence
        var from = start
        while (from < end && text[from].isWhitespace()) from++
        var to = end
        while (to > from && text[to - 1].isWhitespace()) to--
        if (from == to) return null
        return Marked(message, document.createRangeMarker(from, to))
    }

    private fun document(path: String): Document? =
        LocalFileSystem.getInstance().findFileByPath(path)?.let { FileDocumentManager.getInstance().getDocument(it) }

    private fun refresh(source: VirtualFile, paths: Collection<String>) {
        val analyzer = DaemonCodeAnalyzer.getInstance(project)
        val sourcePath = canonical(source.path)
        for (path in paths.toSet()) {
            val file = (if (path == sourcePath) source else LocalFileSystem.getInstance().findFileByPath(path)) ?: continue
            PsiManager.getInstance(project).findFile(file)?.let { analyzer.restart(it, "SCAD Sphere: OpenSCAD messages") }
        }
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    companion object {
        fun getInstance(project: Project): RenderMessages = project.getService(RenderMessages::class.java)
    }
}
