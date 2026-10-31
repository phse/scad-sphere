package app.heckit.ide.scadsphere

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Was sich das Plugin je .scad-Datei merkt (im Workspace, nicht im Projekt): geänderte Customizer-Werte, den
 * gewählten Parametersatz und das letzte Exportziel.
 */
@Service(Service.Level.PROJECT)
@State(name = "ScadSphereFiles", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ScadFileStates : PersistentStateComponent<ScadFileStates.FilesState> {
    class Entry {
        var path: String = ""

        /** Customizer: Name → OpenSCAD-Ausdruck (`80`, `"Text"`, `true`, `[1, 2]`), nur vom Code abweichende Werte */
        var overrides: MutableMap<String, String> = LinkedHashMap()
        var parameterSet: String? = null
        var exportTarget: String? = null

        fun isEmpty() = overrides.isEmpty() && parameterSet == null && exportTarget == null
    }

    class FilesState {
        var files: MutableList<Entry> = ArrayList()
    }

    private var state = FilesState()
    private val listeners = CopyOnWriteArrayList<(String, Any?) -> Unit>()

    override fun getState(): FilesState = FilesState().also { copy -> copy.files = state.files.filterNot { it.isEmpty() }.toMutableList() }

    override fun loadState(loaded: FilesState) {
        state = loaded
    }

    private fun entry(file: VirtualFile): Entry? = state.files.find { it.path == file.path }

    private fun update(file: VirtualFile, source: Any?, change: (Entry) -> Unit) {
        val entry = entry(file) ?: Entry().also { it.path = file.path; state.files += it }
        change(entry)
        if (entry.isEmpty()) state.files -= entry
        listeners.forEach { it(file.path, source) }
    }

    fun overrides(file: VirtualFile): Map<String, String> = entry(file)?.overrides?.toMap().orEmpty()

    fun parameterSet(file: VirtualFile): String? = entry(file)?.parameterSet

    /** Customizer-Werte setzen; [source] bekommt die Änderung nicht noch einmal gemeldet. */
    fun setCustomizer(file: VirtualFile, overrides: Map<String, String>, parameterSet: String?, source: Any?) = update(file, source) {
        it.overrides = LinkedHashMap(overrides)
        it.parameterSet = parameterSet
    }

    fun exportTarget(file: VirtualFile): String? = entry(file)?.exportTarget

    fun setExportTarget(file: VirtualFile, target: String) = update(file, null) { it.exportTarget = target }

    /** Meldet Änderungen (Pfad der Datei, Auslöser) z. B. an eine zweite Vorschau derselben Datei. */
    fun addListener(parent: Disposable, listener: (path: String, source: Any?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    companion object {
        fun of(project: Project): ScadFileStates = project.service()
    }
}
