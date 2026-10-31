package app.heckit.ide.scadsphere.export

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.render.ExportOptions
import app.heckit.ide.scadsphere.render.OpenScadFeatures
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/** Formate, die OpenSCAD schreiben kann; 3D-Formate auch als Ziel beim Umwandeln von Modellen. */
enum class ExportFormat(val extension: String, val label: String, val is3d: Boolean) {
    THREE_MF("3mf", "3MF", true),
    STL("stl", "STL", true),
    OBJ("obj", "OBJ", true),
    OFF("off", "OFF", true),
    AMF("amf", "AMF", true),
    SVG("svg", "SVG", false),
    DXF("dxf", "DXF", false),
    PDF("pdf", "PDF", false),
    ;

    /** Wofür das Format taugt (Beschreibung im Menü). */
    val description: String get() = ScadSphereBundle.message("format.$extension")

    /** Einstellungen, die die installierte Version für dieses Format kennt. */
    fun choices(features: OpenScadFeatures): List<ExportChoice> = buildList {
        when (this@ExportFormat) {
            STL -> if (features.binaryStl) {
                add(ExportChoice("stl.encoding", ScadSphereBundle.message("export.option.encoding"),
                    listOf("binstl" to ScadSphereBundle.message("export.option.encoding.binary"), "asciistl" to ScadSphereBundle.message("export.option.encoding.ascii")),
                    "binstl") { value -> ExportOptions(exportFormat = value) })
            }
            THREE_MF -> {
                features.setting("export-3mf", "color-mode")?.takeIf { "none" in it.values && "model" in it.values }?.let {
                    add(ExportChoice("3mf.colors", ScadSphereBundle.message("export.option.colors"),
                        listOf("model" to ScadSphereBundle.message("export.option.colors.model"), "none" to ScadSphereBundle.message("export.option.colors.none")),
                        "model") { value -> ExportOptions(settings = mapOf("export-3mf/color-mode" to value)) })
                }
                features.setting("export-3mf", "unit")?.takeIf { it.values.isNotEmpty() }?.let { unit ->
                    add(ExportChoice("3mf.unit", ScadSphereBundle.message("export.option.unit"), unit.values.map { it to unitLabel(it) },
                        unit.default ?: "millimeter") { value -> ExportOptions(settings = mapOf("export-3mf/unit" to value)) })
                }
            }
            PDF -> {
                features.setting("export-pdf", "paper-size")?.takeIf { it.values.isNotEmpty() }?.let { size ->
                    add(ExportChoice("pdf.paper", ScadSphereBundle.message("export.option.paper"), size.values.map { it to paperLabel(it) },
                        size.default ?: size.values.first()) { value -> ExportOptions(settings = mapOf("export-pdf/paper-size" to value)) })
                }
                features.setting("export-pdf", "orientation")?.takeIf { it.values.isNotEmpty() }?.let { orientation ->
                    add(ExportChoice("pdf.orientation", ScadSphereBundle.message("export.option.orientation"),
                        orientation.values.map { it to orientationLabel(it) },
                        orientation.default ?: orientation.values.first()) { value -> ExportOptions(settings = mapOf("export-pdf/orientation" to value)) })
                }
            }
            else -> Unit
        }
    }

    /** Zuletzt gewählte (bzw. voreingestellte) Einstellungen als Optionen für OpenSCAD. */
    fun rememberedOptions(features: OpenScadFeatures): ExportOptions =
        choices(features).fold(ExportOptions()) { options, choice -> options + choice.options(choice.remembered()) }

    companion object {
        /** Format des letzten Vorschau-Ergebnisses einer .scad-Datei (`3mf` oder `svg`), für die Auswahl der Formate. */
        val LAST_RENDER_FORMAT = Key.create<String>("scadsphere.lastRenderFormat")

        val MODEL_EXTENSIONS = setOf("stl", "3mf", "obj", "off", "amf")

        fun byExtension(extension: String?): ExportFormat? = entries.find { it.extension.equals(extension, ignoreCase = true) }

        fun isModel(file: VirtualFile): Boolean = !file.isDirectory && file.extension?.lowercase() in MODEL_EXTENSIONS

        private fun unitLabel(value: String) = when (value) {
            "micron" -> "µm"
            "millimeter" -> "mm"
            "centimeter" -> "cm"
            "meter" -> "m"
            "inch" -> ScadSphereBundle.message("export.option.unit.inch")
            "foot" -> ScadSphereBundle.message("export.option.unit.foot")
            else -> value
        }

        private fun paperLabel(value: String) = if (Regex("[aA]\\d").matches(value)) value.uppercase() else value.replaceFirstChar { it.uppercase() }

        private fun orientationLabel(value: String) = when (value) {
            "portrait", "landscape", "auto" -> ScadSphereBundle.message("export.option.orientation.$value")
            else -> value
        }
    }
}

/** Eine Auswahl im Export-Dialog (z. B. binäres oder Text-STL); die letzte Wahl gilt für alle Dateien. */
class ExportChoice(
    val id: String,
    val label: String,
    val values: List<Pair<String, String>>,
    private val default: String,
    private val toOptions: (String) -> ExportOptions,
) {
    fun remembered(): String =
        PropertiesComponent.getInstance().getValue(KEY_PREFIX + id)?.takeIf { value -> values.any { it.first == value } } ?: default

    fun remember(value: String) = PropertiesComponent.getInstance().setValue(KEY_PREFIX + id, value, default)

    fun options(value: String): ExportOptions = toOptions(value)

    private companion object {
        const val KEY_PREFIX = "app.heckit.ide.scadsphere.export."
    }
}

operator fun ExportOptions.plus(other: ExportOptions) = ExportOptions(other.exportFormat ?: exportFormat, settings + other.settings)
