package app.heckit.ide.scadsphere

import com.intellij.ide.util.PropertiesComponent
import com.intellij.util.messages.Topic

/** Einstellungen, für alle Projekte gleich (Settings | Tools | SCAD Sphere). */
object ScadSphereSettings {
    /** Wann die Vorschau einer .scad-Datei neu gerechnet wird. */
    enum class RenderMode { TYPING, SAVE, MANUAL }

    /** Geometrie-Backend für 3D (`--backend`); DEFAULT = was die installierte Version standardmäßig nimmt. */
    enum class Backend(val argument: String?) { DEFAULT(null), MANIFOLD("Manifold"), CGAL("CGAL") }

    /** Pfad zu OpenSCAD; leer = automatisch suchen. */
    var openScadPath: String
        get() = PropertiesComponent.getInstance().getValue(PATH_KEY) ?: ""
        set(value) = PropertiesComponent.getInstance().setValue(PATH_KEY, value.trim(), "")

    /** Slicer für *Open in Slicer*; leer = erster gefundener. */
    var slicerPath: String
        get() = PropertiesComponent.getInstance().getValue(SLICER_KEY) ?: ""
        set(value) = PropertiesComponent.getInstance().setValue(SLICER_KEY, value.trim(), "")

    /** Zusätzliche Bibliotheksordner für include/use, einer je Zeile. */
    var libraryPaths: String
        get() = PropertiesComponent.getInstance().getValue(LIBRARIES_KEY) ?: ""
        set(value) = PropertiesComponent.getInstance().setValue(LIBRARIES_KEY, value, "")

    fun libraryPathList(): List<String> = libraryPaths.lines().map { it.trim() }.filter { it.isNotEmpty() }

    var renderMode: RenderMode
        get() = PropertiesComponent.getInstance().getValue(RENDER_MODE_KEY)?.let { v -> RenderMode.entries.find { it.name == v } } ?: RenderMode.TYPING
        set(value) = PropertiesComponent.getInstance().setValue(RENDER_MODE_KEY, value.name, RenderMode.TYPING.name)

    var backend: Backend
        get() = PropertiesComponent.getInstance().getValue(BACKEND_KEY)?.let { v -> Backend.entries.find { it.name == v } } ?: Backend.DEFAULT
        set(value) = PropertiesComponent.getInstance().setValue(BACKEND_KEY, value.name, Backend.DEFAULT.name)

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

    /** Den Aufruf an der Schreibmarke in der Vorschau hervorheben. */
    var caretHighlight: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(CARET_HIGHLIGHT_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(CARET_HIGHLIGHT_KEY, value, true)

    /** Ausgaben von `echo` am Ende ihrer Zeile im Editor zeigen. */
    var echoInline: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(ECHO_INLINE_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(ECHO_INLINE_KEY, value, true)

    /** Druckbett im Viewer zeigen und prüfen, ob das Modell darauf passt. */
    var bedEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(BED_ENABLED_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(BED_ENABLED_KEY, value, true)

    /** Größe des Druckbetts in mm (Standard: Bambu Lab X1/P1/X2D, einzelne Düse). */
    var bedWidth: Int
        get() = PropertiesComponent.getInstance().getInt(BED_WIDTH_KEY, 256)
        set(value) = PropertiesComponent.getInstance().setValue(BED_WIDTH_KEY, value.coerceIn(10, 5000), 256)
    var bedDepth: Int
        get() = PropertiesComponent.getInstance().getInt(BED_DEPTH_KEY, 256)
        set(value) = PropertiesComponent.getInstance().setValue(BED_DEPTH_KEY, value.coerceIn(10, 5000), 256)
    var bedHeight: Int
        get() = PropertiesComponent.getInstance().getInt(BED_HEIGHT_KEY, 260)
        set(value) = PropertiesComponent.getInstance().setValue(BED_HEIGHT_KEY, value.coerceIn(10, 5000), 260)

    /** Bett um den Ursprung (true) oder ab dem Ursprung als Ecke vorne links. */
    var bedCentered: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(BED_CENTERED_KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(BED_CENTERED_KEY, value, true)

    fun bed(): Map<String, Any> =
        mapOf("enabled" to bedEnabled, "width" to bedWidth, "depth" to bedDepth, "height" to bedHeight, "center" to bedCentered)

    private const val BED_ENABLED_KEY = "app.heckit.ide.scadsphere.bed.enabled"
    private const val BED_WIDTH_KEY = "app.heckit.ide.scadsphere.bed.width"
    private const val BED_DEPTH_KEY = "app.heckit.ide.scadsphere.bed.depth"
    private const val BED_HEIGHT_KEY = "app.heckit.ide.scadsphere.bed.height"
    private const val BED_CENTERED_KEY = "app.heckit.ide.scadsphere.bed.centered"
    private const val SLICER_KEY = "app.heckit.ide.scadsphere.slicerPath"
    private const val PATH_KEY = "app.heckit.ide.scadsphere.openScadPath"
    private const val LIBRARIES_KEY = "app.heckit.ide.scadsphere.libraryPaths"
    private const val RENDER_MODE_KEY = "app.heckit.ide.scadsphere.renderMode"
    private const val BACKEND_KEY = "app.heckit.ide.scadsphere.backend"
    private const val RENDER_DELAY_KEY = "app.heckit.ide.scadsphere.renderDelay"
    private const val SHOW_PREVIEW_KEY = "app.heckit.ide.scadsphere.showPreview"
    private const val CARET_HIGHLIGHT_KEY = "app.heckit.ide.scadsphere.caretHighlight"
    private const val ECHO_INLINE_KEY = "app.heckit.ide.scadsphere.echoInline"
    private const val SHOW_CUSTOMIZER_KEY = "app.heckit.ide.scadsphere.showCustomizer"
}

/** Einstellungen des Viewers (Druckbett) haben sich geändert. */
fun interface ViewerSettingsListener {
    fun changed()

    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("SCAD Sphere viewer settings", ViewerSettingsListener::class.java)
    }
}
