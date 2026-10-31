package app.heckit.ide.scadsphere

import com.intellij.ui.ColoredListCellRenderer
import javax.swing.JList
import javax.swing.ListCellRenderer

/** Listeneintrag als Text (für Auswahllisten); öffentliche API auch in 2026.1, anders als `textListCellRenderer`. */
fun <T> textRenderer(text: (T?) -> String): ListCellRenderer<T> = object : ColoredListCellRenderer<T>() {
    override fun customizeCellRenderer(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        append(text(value))
    }
}
