package app.heckit.ide.scadsphere.customizer

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

/**
 * Parametersätze in `<datei>.json` neben der .scad-Datei, im Format der OpenSCAD-Oberfläche (beide sehen also
 * dieselben Sätze): `{"parameterSets": {"Name": {"breite": "80", …}}, "fileFormatVersion": "1"}`.
 */
object ParameterSets {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun fileName(scad: VirtualFile) = "${scad.nameWithoutExtension}.json"

    fun find(scad: VirtualFile): VirtualFile? = scad.parent?.findChild(fileName(scad))

    /** Satzname → (Parametername → Wert, wie gespeichert). Unlesbare Dateien ergeben keine Sätze. */
    fun read(scad: VirtualFile): Map<String, Map<String, JsonElement>> {
        val file = find(scad) ?: return emptyMap()
        val root = runCatching { JsonParser.parseString(VfsUtil.loadText(file)).asJsonObject }
            .onFailure { thisLogger().info("${file.path}: ${it.message}") }
            .getOrNull() ?: return emptyMap()
        val sets = root.get("parameterSets")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return sets.entrySet().filter { it.value.isJsonObject }.associate { (name, values) ->
            name to values.asJsonObject.entrySet().associate { it.key to it.value }
        }
    }

    /** Satz [name] mit [values] (Parametername → Wert für OpenSCAD) speichern oder mit null löschen. Im Schreibzugriff. */
    fun write(scad: VirtualFile, name: String, values: Map<String, String>?) {
        val existing = find(scad)
        val root = existing?.let { file -> runCatching { JsonParser.parseString(VfsUtil.loadText(file)).asJsonObject }.getOrNull() } ?: JsonObject()
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
            val file = existing ?: scad.parent.createChildData(this, fileName(scad))
            file.setBinaryContent(text.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
