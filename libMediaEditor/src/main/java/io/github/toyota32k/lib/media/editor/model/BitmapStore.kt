package io.github.toyota32k.lib.media.editor.model

import android.graphics.Bitmap
import io.github.toyota32k.utils.IDisposable
import kotlin.collections.forEach

/**
 * Bitmapの recycle を一括管理する
 */
class BitmapStore : IDisposable {
    private val storage: MutableSet<Bitmap> = mutableSetOf()

    /**
     * BitmapStore に Bitmapを追加する。
     */
    fun attach(bitmap: Bitmap):Bitmap {
        storage.add(bitmap)
        return bitmap
    }

    /**
     * Bitmap が BitmapStore に登録されていれば、それを解除して recycle()する。
     */
    fun discard(bitmap: Bitmap) {
        if(storage.remove(bitmap)) {
            bitmap.recycle()
        }
    }

    /**
     * BitmapをBitmapStoreから切り離す。
     */
    fun detach(bitmap: Bitmap):Bitmap {
        storage.remove(bitmap)
        return bitmap
    }

    /**
     * （ビットマップを編集した場合などに）oldをrecycleして、newに置き換える。
     */
    @Suppress("unused")
    fun replace(old: Bitmap, new: Bitmap):Bitmap {
        if (old == new) return new
        discard(old)
        attach(new)
        return new
    }

    /**
     * upsert的なやつ
     */
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

    /**
     * BitmapStore に登録されているすべてのBitmapをrecycle()する。
     */
    override fun dispose() {
        storage.forEach {
            it.recycle()
        }
        storage.clear()
    }
}