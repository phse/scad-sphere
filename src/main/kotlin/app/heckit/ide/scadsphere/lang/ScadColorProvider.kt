package app.heckit.ide.scadsphere.lang

import app.heckit.ide.scadsphere.ScadSphereBundle
import app.heckit.ide.scadsphere.psi.ScadModuleCall
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ElementColorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import java.awt.Color
import java.util.Locale

/**
 * Farbfeld im Gutter neben `color(…)` mit Farbwähler: Name (`"red"`), Hex (`"#ff0000"`, `"#f00"`, mit Alpha) oder
 * Vektor aus Zahlen 0…1 (`[1, 0, 0]`, `[1, 0, 0, 0.5]`). Der Wähler schreibt im selben Stil zurück, Namen werden zu Hex.
 */
class ScadColorProvider : ElementColorProvider {
    override fun getColorFrom(element: PsiElement): Color? {
        if (element.node.elementType != ScadTypes.IDENTIFIER || element.text != "color") return null
        val call = element.parent as? ScadModuleCall ?: return null
        if (call.nameElement != element) return null
        return colorArgument(call)?.let { parse(it.text) }
    }

    override fun setColorTo(element: PsiElement, color: Color) {
        val call = element.parent as? ScadModuleCall ?: return
        val argument = colorArgument(call) ?: return
        val file = element.containingFile ?: return
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return
        val text = format(argument.text, color)
        val range = argument.textRange
        WriteCommandAction.runWriteCommandAction(element.project, ScadSphereBundle.message("color.change"), UNDO_GROUP, {
            document.replaceString(range.startOffset, range.endOffset, text)
            PsiDocumentManager.getInstance(element.project).commitDocument(document)
        }, file)
    }

    /** Wert des ersten Arguments (positionell oder `c = …`), sofern es ein Literal ist. */
    private fun colorArgument(call: ScadModuleCall): PsiElement? {
        val argument = call.arguments.firstOrNull { it.argumentName?.text == "c" }
            ?: call.arguments.firstOrNull()?.takeIf { it.argumentName == null }
            ?: return null
        val value = argument.value ?: return null
        return value.takeIf { it.node.elementType == ScadTypes.LITERAL_EXPRESSION || it.node.elementType == ScadTypes.VECTOR_EXPRESSION }
    }

    companion object {
        /** Der Farbwähler setzt die Farbe bei jeder Bewegung neu - alle Schritte ergeben einen Undo-Schritt. */
        private const val UNDO_GROUP = "ScadSphere.ChangeColor"

        fun parse(text: String): Color? {
            val t = text.trim()
            if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) {
                val s = t.substring(1, t.length - 1).trim().lowercase(Locale.ROOT)
                if (s.startsWith("#")) return hex(s.substring(1))
                return NAMES[s]?.let { hex(it) }
            }
            if (t.startsWith("[") && t.endsWith("]")) {
                val parts = t.substring(1, t.length - 1).split(',').map { it.trim().toDoubleOrNull() ?: return null }
                if (parts.size !in 3..4 || parts.any { it < 0 || it > 1 }) return null
                return Color(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts.getOrElse(3) { 1.0 }.toFloat())
            }
            return null
        }

        fun format(old: String, color: Color): String {
            val t = old.trim()
            if (t.startsWith("[")) {
                val alpha = t.substring(1, t.length - 1).split(',').size == 4 || color.alpha < 255
                val values = listOf(color.red, color.green, color.blue) + if (alpha) listOf(color.alpha) else emptyList()
                return values.joinToString(", ", "[", "]") { number(it / 255.0) }
            }
            val withAlpha = color.alpha < 255 || (t.removeSurrounding("\"").let { it.startsWith("#") && (it.length == 5 || it.length == 9) })
            val hex = String.format(Locale.ROOT, "#%02x%02x%02x", color.red, color.green, color.blue) +
                if (withAlpha) String.format(Locale.ROOT, "%02x", color.alpha) else ""
            return "\"$hex\""
        }

        private fun number(value: Double): String {
            val rounded = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
            return if (rounded == "-0") "0" else rounded
        }

        private fun hex(s: String): Color? {
            val full = when (s.length) {
                3, 4 -> s.map { "$it$it" }.joinToString("")
                6, 8 -> s
                else -> return null
            }
            val v = full.chunked(2).map { it.toIntOrNull(16) ?: return null }
            return Color(v[0], v[1], v[2], v.getOrElse(3) { 255 })
        }

        /** SVG/CSS-Farbnamen, wie OpenSCAD sie kennt */
        private val NAMES: Map<String, String> = """
            aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4
            black 000000 blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0
            chartreuse 7fff00 chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff
            darkblue 00008b darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9
            darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000
            darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f
            darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969
            dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc
            ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080
            honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa
            lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff
            lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1
            lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 lightslategrey 778899
            lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000
            mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371
            mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585
            midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080
            oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa
            palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f
            pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f
            royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee
            sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa
            springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0
            violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32
        """.trim().split(Regex("\\s+")).chunked(2).associate { (name, hex) -> name to hex }
    }
}
