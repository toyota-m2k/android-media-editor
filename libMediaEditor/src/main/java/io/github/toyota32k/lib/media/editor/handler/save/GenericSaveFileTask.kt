package io.github.toyota32k.lib.media.editor.handler.save

import androidx.lifecycle.LifecycleOwner
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.lifecycle.Listeners

interface ISavedListener {
    fun addOnSavedListener(fn:(ISaveResult)->Unit): IDisposable
    fun addOnSavedListener(owner:LifecycleOwner, fn:(ISaveResult)->Unit): IDisposable
}

class SavedListenerImpl : ISavedListener {
    private val listeners = Listeners<ISaveResult>()
    override fun addOnSavedListener(fn:(ISaveResult)->Unit): IDisposable {
        return listeners.addForever(fn)
    }
    override fun addOnSavedListener(owner:LifecycleOwner, fn:(ISaveResult)->Unit): IDisposable {
        return listeners.add(owner, fn)
    }

    fun fireOnSaved(value: ISaveResult) {
        listeners.invoke(value)
    }
}

abstract class AbstractProgressSaveFileTask private constructor(val savedListener: SavedListenerImpl) : ISaveFileTask, IProgressSinkProvider, ISavedListener by savedListener {
    constructor() : this(SavedListenerImpl())

    open val logger = UtLog("SaveTask", AmeGlobal.logger)

    override var progressSink: IProgressSink? = null

    /**
     * デフォルトでは ProgressDialogを使って進捗を表示する。
     * これを変更したければ、このメソッドをオーバーライドする。
     */
    open suspend fun openProgressSink(canceller:ICanceller?):IProgressSink? {
        return ProgressDialog.showProgressDialog("Save File", canceller)
    }

    /**
     * １ファイル保存開始イベント
     */
    override suspend fun onStart(taskStatus: SaveTaskStatus, canceller: ICanceller?) {
        logger.debug(taskStatus.message)
        if (progressSink==null) {
            progressSink = openProgressSink(canceller)
        }
    }

    /**
     * １ファイル保存終了イベント
     */
    override suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult) {
        // nothing to do.
        logger.debug(taskStatus.message)
        savedListener.fireOnSaved(result)
    }

    /**
     * 全ファイル保存完了イベント
     */
    override suspend fun onFinished() {
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

open class GenericSaveImageTask(private val savedListener: SavedListenerImpl) : ISaveImageTask {
    constructor():this(SavedListenerImpl())

    override suspend fun onStart(taskStatus: SaveTaskStatus, canceller: ICanceller?) {
    }

    override suspend fun onEnd(taskStatus: SaveTaskStatus, result: ISaveResult) {
        savedListener.fireOnSaved(result)
    }

    override suspend fun onFinished() {
    }
    companion object {
        fun defaultTask(): ISaveImageTask {
            return GenericSaveImageTask()
        }
    }
}
