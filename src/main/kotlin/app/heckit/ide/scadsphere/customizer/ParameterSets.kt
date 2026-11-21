package app.heckit.ide.scadsphere.customizer

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Parametersätze in `<datei>.json` neben der .scad-Datei, im Format der OpenSCAD-Oberfläche (beide sehen also
 * dieselben Sätze): `{"parameterSets": {"Name": {"breite": "80", …}}, "fileFormatVersion": "1"}`.
 */
object ParameterSets {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun fileName(scad: VirtualFile) = "${scad.nameWithoutExtension}.json"

    /**
     * Die Datei der Sätze. Per Shell daneben gelegte Dateien kennt das VFS noch nicht (`findChild` liefert dann null):
     * Liegt sie auf der Platte, wird sie nachgeladen - außerhalb einer Lesesperre sofort, sonst im Hintergrund
     * ([read] liest sie bis dahin direkt von der Platte).
     */
    fun find(scad: VirtualFile): VirtualFile? {
        val dir = scad.parent ?: return null
        dir.findChild(fileName(scad))?.let { return it }
        val io = File(dir.path, fileName(scad))
        if (!io.isFile) return null
        val application = ApplicationManager.getApplication()
        if (application.isReadAccessAllowed) {
            LocalFileSystem.getInstance().refreshIoFiles(listOf(io), true, false, null)
            return null
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
    }

    /** Satzname → (Parametername → Wert, wie gespeichert). Unlesbare Dateien ergeben keine Sätze. */
    fun read(scad: VirtualFile): Map<String, Map<String, JsonElement>> {
        val file = find(scad)
        val io = File(scad.parent?.path ?: return emptyMap(), fileName(scad))
        if (file == null && !io.isFile) return emptyMap()
        val root = runCatching { JsonParser.parseString(if (file != null) VfsUtil.loadText(file) else io.readText()).asJsonObject }
            .onFailure { thisLogger().info("${io.path}: ${it.message}") }
            .getOrNull() ?: return emptyMap()
        val sets = root.get("parameterSets")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return sets.entrySet().filter { it.value.isJsonObject }.associate { (name, values) ->
            name to values.asJsonObject.entrySet().associate { it.key to it.value }
        }
    }

    /** Satz [name] mit [values] (Parametername → Wert für OpenSCAD) speichern oder mit null löschen. Im Schreibzugriff. */
    fun write(scad: VirtualFile, name: String, values: Map<String, String>?) {
        val existing = find(scad)
        val io = File(scad.parent.path, fileName(scad))
        val oldText = existing?.let { VfsUtil.loadText(it) } ?: io.takeIf { it.isFile }?.readText()
        val root = oldText?.let { text -> runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() } ?: JsonObject()
        val sets = root.get("parameterSets")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (values == null) {
            sets.remove(name)
        } else {
            sets.add(name, JsonObject().apply { values.forEach { (key, value) -> add(key, JsonPrimitive(value)) } })
        }
        root.add("parameterSets", sets)
        if (!root.has("fileFormatVersion")) root.addProperty("fileFormatVersion", "1")
        val text = gson.toJson(root) + "\n"
        WriteAction.run<Throwable> {
            val file = existing ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io) ?: scad.parent.createChildData(this, fileName(scad))
            file.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
