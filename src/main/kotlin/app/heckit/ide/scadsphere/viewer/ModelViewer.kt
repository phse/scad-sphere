package app.heckit.ide.scadsphere.viewer

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.ScadSphereSettings
import app.heckit.ide.scadsphere.ViewerSettingsListener
import app.heckit.ide.scadsphere.render.RenderMessage
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.handler.CefContextMenuHandlerAdapter
import java.awt.Color
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * 3D-Ansicht im eingebetteten Chromium (three.js, siehe web/). Das Modell geht als Datei über eine eigene
 * Adresse hinein ([ViewerResources]), Befehle per JavaScript; Rückmeldungen (bereit, Zeile öffnen,
 * Link angeklickt) kommen über eine [JBCefJSQuery].
 */
class ModelViewer(parent: Disposable) : Disposable {
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser.createBuilder().build() else null
    private val query: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val gson = Gson()

    /** Modell und ggf. die #/%-Teile (3MF), abrufbar unter `model/<id>` */
    @Volatile
    private var models: Map<String, ByteArray> = emptyMap()
    private val modelCounter = AtomicInteger()

    /** JavaScript-Aufrufe, bis die Seite bereit ist. */
    private val pending = mutableListOf<String>()
    private var ready = false

    @Volatile
    private var disposed = false

    /** Klick auf eine Meldung mit Datei und Zeile. */
    var onNavigate: ((file: String?, line: Int) -> Unit)? = null

    /** Klick auf einen Link im Hinweis (z. B. „settings“). */
    var onAction: ((String) -> Unit)? = null

    val component: JComponent = browser?.component ?: JBLabel(ScadSphereBundle.message("viewer.noJcef"), SwingConstants.CENTER)

    init {
        Disposer.register(parent, this)
        if (browser != null && query != null) {
            Disposer.register(this, browser)
            Disposer.register(this, query)
            query.addHandler { request ->
                ApplicationManager.getApplication().invokeLater({ onMessage(request) }, { disposed })
                JBCefJSQuery.Response("")
            }
            browser.jbCefClient.addRequestHandler(ViewerResources(::initScript) { id -> models[id] }, browser.cefBrowser)
            browser.jbCefClient.addContextMenuHandler(NoContextMenu, browser.cefBrowser)
            val connection = ApplicationManager.getApplication().messageBus.connect(this)
            connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })
            connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { applyTheme() })
            connection.subscribe(ViewerSettingsListener.TOPIC, ViewerSettingsListener { call("setBed", ScadSphereSettings.bed()) })
            browser.loadURL(ViewerResources.START_URL)
        }
    }

    /** Modell anzeigen; [format]: `stl`, `3mf`, `obj`, `off`, `amf` oder `svg` (2D von OpenSCAD). [keepCamera]: Blickwinkel behalten. */
    fun showModel(bytes: ByteArray, format: String, keepCamera: Boolean, overlay: ByteArray? = null) {
        val number = modelCounter.incrementAndGet()
        val id = "$number.$format"
        val overlayId = "$number-overlay.3mf"
        models = if (overlay != null) mapOf(id to bytes, overlayId to overlay) else mapOf(id to bytes)
        call("load", mapOf(
            "url" to "${ViewerResources.ORIGIN}/model/$id",
            "format" to format,
            "keepCamera" to keepCamera,
            "overlayUrl" to overlay?.let { "${ViewerResources.ORIGIN}/model/$overlayId" },
        ))
    }

    /** Kleiner Hinweis oben links mit Ladekreis (z. B. „Rechnet…“); null blendet ihn aus. */
    fun setStatus(text: String?) = call("setStatus", text)

    /** Hinweis statt Modell, optional mit Link, der [onAction] mit [action] auslöst. */
    fun showEmpty(text: String, linkLabel: String? = null, action: String? = null, keepModel: Boolean = false) {
        if (!keepModel) models = emptyMap()
        val link = if (linkLabel != null && action != null) mapOf("label" to linkLabel, "action" to action) else null
        call("showEmpty", mapOf("text" to text, "link" to link, "keepModel" to keepModel))
    }

    /**
     * Knöpfe der Vorschau in der Werkzeugleiste: Neu rechnen (bzw. Abbrechen, solange [rendering]) und Customizer
     * ein/aus ([customizerEnabled]: die Datei hat Parameter). Klicks kommen als Aktion `render` bzw. `customizer`.
     */
    fun setTools(rendering: Boolean, customizerShown: Boolean, customizerEnabled: Boolean) =
        call("setTools", mapOf("render" to true, "rendering" to rendering, "customizer" to customizerShown, "customizerEnabled" to customizerEnabled))

    /** Beliebigen Aufruf an `window.scadSphere` schicken (Screenshots, Tests), z. B. `setSection` mit Argument. */
    fun invoke(function: String, argument: Any?) = call(function, argument)

    /** Meldungsliste aufklappen (von selbst nur bei Fehlern). */
    fun expandMessages() = call("expandMessages", null)

    fun setMessages(messages: List<RenderMessage>) {
        call("setMessages", messages.map { message ->
            mapOf(
                "level" to message.level.name.lowercase(),
                "text" to message.text,
                "file" to message.file,
                "line" to message.line,
                "where" to message.line?.let { line -> "${message.file?.let { File(it).name } ?: ""}:$line" },
            )
        })
    }

    private fun call(function: String, argument: Any?) {
        val script = "window.scadSphere && window.scadSphere.$function(${gson.toJson(argument)})"
        ApplicationManager.getApplication().invokeLater({
            if (ready) execute(script) else pending += script
        }, { disposed })
    }

    private fun execute(script: String) {
        browser?.cefBrowser?.executeJavaScript(script, ViewerResources.ORIGIN, 0)
    }

    private fun onMessage(request: String) {
        val message = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull() ?: return
        when (message.get("type")?.asString) {
            "ready" -> {
                ready = true
                applyTheme()
                pending.forEach(::execute)
                pending.clear()
            }
            "navigate" -> {
                val line = message.get("line")?.takeIf { !it.isJsonNull }?.asInt ?: return
                val file = message.get("file")?.takeIf { !it.isJsonNull }?.asString
                onNavigate?.invoke(file, line)
            }
            "action" -> message.get("action")?.asString?.let { onAction?.invoke(it) }
            "error" -> thisLogger().warn("Viewer: ${message.get("message")}")
        }
    }

    /** Start-Skript vor allen Skripten der Seite: Texte, Farben und die Brücke zum Plugin. */
    private fun initScript(): String {
        val init = mapOf(
            "labels" to labels(),
            "theme" to theme(),
            "locale" to Locale.getDefault().toLanguageTag(),
            "bed" to ScadSphereSettings.bed(),
        )
        val send = query?.inject("JSON.stringify(message)") ?: ""
        return """
            window.__scadSphereInit = ${gson.toJson(init)};
            window.scadSphereHost = { send(message) { $send } };
        """.trimIndent()
    }

    private fun labels(): Map<String, String> = listOf(
        "tool.fit", "tool.iso", "tool.top", "tool.front", "tool.right", "tool.colors", "tool.colors.none", "tool.edges", "tool.ortho", "tool.grid",
        "tool.render", "tool.stop", "tool.customizer", "tool.customizer.none",
        "messages.error", "messages.warning", "messages.echo", "triangles", "parseError", "noWebGl",
        "tool.bed", "tool.bed.none", "tool.measure", "tool.measure.none", "tool.section", "tool.section.none",
        "section.flip", "bed.tooLarge", "bed.outside", "bed.below",
        "tool.views", "tool.display", "tool.more", "tool.left", "tool.back", "tool.bottom",
    ).associateWith { ScadSphereBundle.message("viewer.$it") }

    private fun applyTheme() {
        if (!ready) return
        execute("window.scadSphere && window.scadSphere.setTheme(${gson.toJson(theme())})")
    }

    /** Farben passend zum IDE-Theme; Hintergrund wie der Editor. */
    private fun theme(): Map<String, Any> {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val dark = !JBColor.isBright()
        val background = scheme.defaultBackground
        val font = UIUtil.getLabelFont()
        return mapOf(
            "dark" to dark,
            "bg" to css(background),
            "fg" to css(scheme.defaultForeground),
            "muted" to css(UIUtil.getContextHelpForeground()),
            "panel" to css(background, 0.92),
            "border" to css(JBColor.border()),
            "hover" to if (dark) "rgba(255, 255, 255, 0.08)" else "rgba(0, 0, 0, 0.06)",
            "active" to css(JBUI.CurrentTheme.Link.Foreground.ENABLED),
            "error" to css(NamedColorUtil.getErrorForeground()),
            "warning" to if (dark) "#e5a00d" else "#b26b00",
            "model" to "#f9d72c",
            "grid" to if (dark) "#393b40" else "#dcdfe5",
            "gridMajor" to if (dark) "#4e5157" else "#b8bcc6",
            "edges" to if (dark) "#101113" else "#2b2d30",
            "font" to font.family,
            "fontSize" to font.size,
        )
    }

    /** Deckend als `#rrggbb` (versteht auch three.js), sonst `rgba(…)`. */
    private fun css(color: Color, alpha: Double = color.alpha / 255.0): String =
        if (alpha >= 1.0) "#%02x%02x%02x".format(color.red, color.green, color.blue)
        else "rgba(${color.red}, ${color.green}, ${color.blue}, $alpha)"

    override fun dispose() {
        disposed = true
    }
}

/** Chromiums Kontextmenü (Zurück, Neu laden, …) hat im Viewer nichts verloren. */
private object NoContextMenu : CefContextMenuHandlerAdapter() {
    override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel?) {
        model?.clear()
    }
}
