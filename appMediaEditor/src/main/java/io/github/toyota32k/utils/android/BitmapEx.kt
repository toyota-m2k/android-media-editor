package io.github.toyota32k.utils.android

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Bitmap を参照カウンタで管理するクラス。
 * 作成時点では参照カウンタはゼロ。保持する場合は addRef()、そうでない場合は release()する。
 */
class RefBitmap(bmp:Bitmap) {
    private var bitmap:Bitmap? = bmp
        private set
    private var refCount: Int = 0
    fun addRef(): RefBitmap {
        if (!hasBitmap) {
            throw IllegalStateException("bitmap is already recycled")
        }
        refCount++
        return this
    }
    fun release() {
        refCount--
        if (refCount<=0) {
            bitmap?.recycle()
            bitmap = null
        }
    }
    val hasBitmap get() = bitmap?.isRecycled==false

    companion object {
        fun from(bmp:Bitmap?):RefBitmap? {
            return bmp?.let { RefBitmap(it) }
        }
        fun Bitmap.toRef():RefBitmap {
            return RefBitmap(this)
        }
    }
}

/**
 * RefBitmap を保持するクラス。
 * このオブジェクトに RefBitmapを設定する（set()/constructor)ときに、自動的にaddRef()され、
 * reset()または、close()でrelease()される。
 * また、他のRefBitmap(null可）をセットすると、古いRefBitmapはreleaseされる。
 *
 * ReadWritePropertyを継承しており、ViewやViewModelのメンバ変数（フィールド）として利用されることを想定。
 *     var bmp: RefBitmap by RefBitmapHolder()
 */
class RefBitmapHolder(): Closeable, ReadWriteProperty<Any, RefBitmap?> {
    constructor(bx:RefBitmap):this() { refBitmap = bx.apply { addRef()} }
    var refBitmap: RefBitmap? = null
        private set

    fun set(br:RefBitmap?) {
        // old == br の場合に備え、先に addRef()する
        val old = refBitmap
        refBitmap = br?.apply { addRef() }
        old?.release()
    }
    fun reset() {
        refBitmap?.release()
        refBitmap = null
    }

    fun get():RefBitmap {
        val ref = refBitmap
        return if (ref?.hasBitmap==true) ref else throw IllegalStateException("bitmap is already recycled")
    }
    fun getOrNull():RefBitmap? {
        val ref = refBitmap
        return if (ref?.hasBitmap==true) ref else null
    }

    fun dup():RefBitmapHolder {
        return RefBitmapHolder().apply { set(refBitmap) }
    }

    val hasBitmap:Boolean get() = refBitmap?.hasBitmap==true

    override fun close() {
        reset()
    }

    override fun getValue(thisRef: Any, property: KProperty<*>): RefBitmap? {
        return getOrNull()
    }

    override fun setValue(thisRef: Any, property: KProperty<*>, value: RefBitmap?) {
        set(value)
    }

}

/**
 * RefBitmap を保持する MutableStateFlow
 * val refBitmapFlow = RefBitmapFlow()
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class RefBitmapFlow private constructor(private val flow: MutableStateFlow<RefBitmap?>) : MutableStateFlow<RefBitmap?> by flow {
    constructor(ref:RefBitmap?=null):this(MutableStateFlow(ref)) { holder.set(ref) }
    val holder = RefBitmapHolder()

    override var value: RefBitmap?
        get() = holder.getOrNull()
        set(value) {
            holder.set(value)
            flow.value = value
        }
}