package app.heckit.ide.scadsphere

import com.intellij.ide.util.PropertiesComponent

/** Einstellungen, für alle Projekte gleich (Settings | Tools | SCAD Sphere). */
object ScadSphereSettings {
    /** Wann die Vorschau einer .scad-Datei neu gerechnet wird. */
    enum class RenderMode { TYPING, SAVE, MANUAL }

    /** Pfad zu OpenSCAD; leer = automatisch suchen. */
    var openScadPath: String
        get() = PropertiesComponent.getInstance().getValue(PATH_KEY) ?: ""
        set(value) = PropertiesComponent.getInstance().setValue(PATH_KEY, value.trim(), "")

    /** Zusätzliche Bibliotheksordner für include/use, einer je Zeile. */
    var libraryPaths: String
        get() = PropertiesComponent.getInstance().getValue(LIBRARIES_KEY) ?: ""
        set(value) = PropertiesComponent.getInstance().setValue(LIBRARIES_KEY, value, "")

    fun libraryPathList(): List<String> = libraryPaths.lines().map { it.trim() }.filter { it.isNotEmpty() }

    var renderMode: RenderMode
        get() = PropertiesComponent.getInstance().getValue(RENDER_MODE_KEY)?.let { v -> RenderMode.entries.find { it.name == v } } ?: RenderMode.TYPING
        set(value) = PropertiesComponent.getInstance().setValue(RENDER_MODE_KEY, value.name, RenderMode.TYPING.name)

    /** Wartezeit nach dem letzten Tastendruck, bevor die Vorschau neu gerechnet wird (ms). */
    var renderDelay: Int
        get() = PropertiesComponent.getInstance().getInt(RENDER_DELAY_KEY, 800)
        set(value) = PropertiesComponent.getInstance().setValue(RENDER_DELAY_KEY, value.coerceIn(200, 10000), 800)

    /** .scad-Dateien mit Vorschau daneben öffnen (sonst nur der Editor; umschalten oben rechts). */
    var showPreview: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SHOW_PREVIEW_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(SHOW_PREVIEW_KEY, value, true)

    /** Customizer neben der Vorschau zeigen (sofern die Datei Parameter hat). */
    var showCustomizer: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SHOW_CUSTOMIZER_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(SHOW_CUSTOMIZER_KEY, value, true)

    private const val PATH_KEY = "app.heckit.ide.scadsphere.openScadPath"
    private const val LIBRARIES_KEY = "app.heckit.ide.scadsphere.libraryPaths"
    private const val RENDER_MODE_KEY = "app.heckit.ide.scadsphere.renderMode"
    private const val RENDER_DELAY_KEY = "app.heckit.ide.scadsphere.renderDelay"
    private const val SHOW_PREVIEW_KEY = "app.heckit.ide.scadsphere.showPreview"
    private const val SHOW_CUSTOMIZER_KEY = "app.heckit.ide.scadsphere.showCustomizer"
}
