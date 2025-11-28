package io.github.toyota32k.lib.media.editor.handler.save

import androidx.lifecycle.LifecycleOwner
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.converter.ICancellable
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.lifecycle.Listeners

interface ISavedListener<T> {
    fun addOnSavedListener(fn:(T)->Unit): IDisposable
    fun addOnSavedListener(owner:LifecycleOwner, fn:(T)->Unit): IDisposable
}

class SavedListenerImpl<T> : ISavedListener<T> {
    private val listeners = Listeners<T>()
    override fun addOnSavedListener(fn:(T)->Unit): IDisposable {
        return listeners.addForever(fn)
    }
    override fun addOnSavedListener(owner:LifecycleOwner, fn:(T)->Unit): IDisposable {
        return listeners.add(owner, fn)
    }

    fun onSaveTaskCompleted(value: T) {
        listeners.invoke(value)
    }
}

abstract class AbstractProgressSaveFileTask : ISaveFileTask, IProgressSinkProvider {
    open val logger = UtLog("SaveTask", AmeGlobal.logger)

    override var progressSink: IProgressSink? = null

    /**
     * デフォルトでは ProgressDialogを使って進捗を表示する。
     * これを変更したければ、このメソッドをオーバーライドする。
     */
    open suspend fun openProgressSink(canceller: ICancellable?):IProgressSink? {
        return ProgressDialog.showProgressDialog("Save File", canceller)
    }

    /**
     * １ファイル保存開始イベント
     */
    override suspend fun onStart(canceller: ICancellable?) {
        logger.debug()
        if (progressSink==null) {
            progressSink = openProgressSink(canceller)
        }
    }

    /**
     * １ファイル保存終了イベント
     */
    override suspend fun onEnd() {
        logger.debug()
        progressSink?.complete()
    }
}


/**
 * ISaveVideoTaskの汎用実装
 * - ProgressDialog を使って進捗を表示する
 * - コーデックは、IVideoStrategySelector, IAudioStrategySelector によって選択される。
 */
open class GenericSaveVideoTask(
    val videoStrategySelector: IVideoStrategySelector,
    val audioStrategySelector: IAudioStrategySelector,
    private val mKeepHdr: Boolean,
    override val fastStart: Boolean,
) : AbstractProgressSaveFileTask(), ISaveVideoTask, IVideoStrategySelector by videoStrategySelector, IAudioStrategySelector by audioStrategySelector {
    override val keepHdr: Boolean
        get() = if (videoStrategySelector is IVideoStrategyAndHdrSelector) {
                videoStrategySelector.keepHdr
            } else {
                mKeepHdr
            }

    companion object {
        fun defaultTask(
            videoStrategySelector: IVideoStrategySelector,
            audioStrategySelector: IAudioStrategySelector,
            keepHdr: Boolean = true,
            fastStart: Boolean = true,
        ): ISaveVideoTask {
//            val outputFile = selectFile("video/mp4", baseName, ".mp4") ?: return null
            return GenericSaveVideoTask(videoStrategySelector, audioStrategySelector, keepHdr, fastStart)
        }
    }
}

open class GenericSaveImageTask : ISaveImageTask {

    override suspend fun onStart(canceller: ICancellable?) {
    }

    override suspend fun onEnd() {
    }

    companion object {
        fun defaultTask(): ISaveImageTask {
            return GenericSaveImageTask()
        }
    }
}
