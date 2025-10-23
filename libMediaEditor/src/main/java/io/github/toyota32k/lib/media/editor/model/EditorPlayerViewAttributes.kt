package io.github.toyota32k.lib.media.editor.model

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.annotation.StyleableRes
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.android.StyledAttrRetriever
import java.lang.AutoCloseable

class EditorPlayerViewAttributes(context: Context, attrs: AttributeSet?, @AttrRes defStyleAttr: Int, @StyleRes defStyleRes:Int=0): AutoCloseable {
    val sarForPlayer: StyledAttrRetriever = StyledAttrRetriever(context, attrs, io.github.toyota32k.lib.player.R.styleable.ControlPanel, defStyleAttr, defStyleRes)
    val sarForEditor: StyledAttrRetriever = StyledAttrRetriever(context, attrs, R.styleable.MediaEditor, defStyleAttr, defStyleRes)
    override fun close() {
        sarForPlayer.close()
        sarForEditor.close()
    }
}