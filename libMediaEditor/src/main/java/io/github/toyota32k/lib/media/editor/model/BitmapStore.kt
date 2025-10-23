package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import io.github.toyota32k.utils.IDisposable
import kotlin.collections.forEach

class BitmapStore : IDisposable {
    private val storage: MutableSet<Bitmap> = mutableSetOf()

    fun attach(bitmap: Bitmap):Bitmap {
        storage.add(bitmap)
        return bitmap
    }

    fun discard(bitmap: Bitmap) {
        if(storage.remove(bitmap)) {
            bitmap.recycle()
        }

    }
    fun detach(bitmap: Bitmap):Bitmap {
        storage.remove(bitmap)
        return bitmap
    }

    fun replace(old: Bitmap, new: Bitmap):Bitmap {
        if (old == new) return new
        discard(old)
        attach(new)
        return new
    }
    fun replaceNullable(old: Bitmap?, new: Bitmap?):Bitmap? {
        if (old == new) return new
        if (old!=null) {
            discard(old)
        }
        if (new!=null) {
            attach(new)
        }
        return new
    }

    override fun dispose() {
        storage.forEach {
            it.recycle()
        }
        storage.clear()
    }
}