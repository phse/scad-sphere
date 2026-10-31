package app.heckit.ide.scadsphere.viewer

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.net.URI

/**
 * Liefert den Viewer (esbuild-Build unter /scadsphere-web/ im Plugin) und das aktuelle Modell unter einer festen
 * HTTPS-Adresse aus. In die index.html wird vorher das Start-Skript (Texte, Farben, Brücke) eingesetzt.
 */
class ViewerResources(
    private val initScript: () -> String,
    private val model: (String) -> ByteArray?,
) : CefRequestHandlerAdapter() {
    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler? {
        if (!request.url.startsWith(ORIGIN)) return null
        return object : CefResourceRequestHandlerAdapter() {
            override fun getResourceHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest): CefResourceHandler =
                load(URI(request.url).path.trimStart('/').ifEmpty { "index.html" })
        }
    }

    private fun load(path: String): CefResourceHandler {
        if (path.startsWith("model/")) {
            val bytes = model(path.removePrefix("model/"))
                ?: return BytesResourceHandler(404, "text/plain", "Not found".toByteArray())
            return BytesResourceHandler(200, "application/octet-stream", bytes)
        }
        val stream = (if (".." in path) null else javaClass.getResourceAsStream("/scadsphere-web/$path"))
            ?: return BytesResourceHandler(404, "text/plain", "Not found: $path".toByteArray())
        var bytes = stream.use { it.readBytes() }
        if (path == "index.html") {
            bytes = bytes.toString(Charsets.UTF_8).replaceFirst("<head>", "<head>\n<script>${initScript()}</script>").toByteArray()
        }
        return BytesResourceHandler(200, mimeType(path), bytes)
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.')) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        else -> "application/octet-stream"
    }

    companion object {
        const val ORIGIN = "https://scadsphere.ide"
        const val START_URL = "$ORIGIN/index.html"
    }
}

/** Antwort aus dem Speicher. */
private class BytesResourceHandler(
    private val status: Int,
    private val mimeType: String,
    private val bytes: ByteArray,
) : CefResourceHandlerAdapter() {
    private var offset = 0

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.status = status
        response.mimeType = mimeType
        // Neue Plugin-Version bzw. neues Modell: nichts aus dem Cache nehmen
        response.setHeaderByName("Cache-Control", "no-store", true)
        responseLength.set(bytes.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        if (offset >= bytes.size) {
            bytesRead.set(0)
            return false
        }
        val count = minOf(bytesToRead, bytes.size - offset)
        System.arraycopy(bytes, offset, dataOut, 0, count)
        offset += count
        bytesRead.set(count)
        return true
    }
}
