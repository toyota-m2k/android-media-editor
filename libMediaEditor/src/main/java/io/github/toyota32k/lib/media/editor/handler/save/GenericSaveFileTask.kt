package io.github.toyota32k.lib.media.editor.handler.save

import androidx.lifecycle.LifecycleOwner
import io.github.toyota32k.lib.media.editor.dialog.ProgressDialog
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.media.editor.model.ISaveListener
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.lib.processor.contract.ICancellable
import io.github.toyota32k.utils.IDisposable
import io.github.toyota32k.utils.lifecycle.Listeners

/**
 * ISaveListener の実装クラス
 */
class SaveTaskListenerImpl<S,E> : ISaveListener<S, E> {
    private val onSavingListeners = Listeners<S>()
    private val onSavedListeners = Listeners<E>()

    override fun addOnSavingListener(owner: LifecycleOwner, fn: (S) -> Unit): IDisposable {
        return onSavingListeners.add(owner, fn)
    }

    override fun addOnSavingListener(fn: (S) -> Unit): IDisposable {
        return onSavingListeners.addForever(fn)
    }

    override fun addOnSavedListener(fn:(E)->Unit): IDisposable {
        return onSavedListeners.addForever(fn)
    }
    override fun addOnSavedListener(owner:LifecycleOwner, fn:(E)->Unit): IDisposable {
        return onSavedListeners.add(owner, fn)
    }

    fun onSaveTaskStarted(value: S) {
        onSavingListeners.invoke(value)
    }

    fun onSaveTaskCompleted(value: E) {
        onSavedListeners.invoke(value)
    }
}

/**
 * ISaveFileTaskの共通実装クラス
 */
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
 * ISaveVideoTaskの動画用実装
 * - ProgressDialog を使って進捗を表示する
 * - コーデックは、IVideoStrategySelector, IAudioStrategySelector によって選択される。
 *
 * @param videoStrategySelector 動画コーデック IVideoStrategy を選択・取得するためのオブジェクトを渡す。
 * @param audioStrategySelector 音声コーデック IAudioStrategy を選択・取得するためのオブジェクトを渡す。
 * @param mKeepHdr  動画エンコード時に、元動画の HDR を維持する場合は true
 * @param fastStart 出力動画ファイルに fast start を実施する場合は true
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

/**
 * ISaveVideoTaskの画像用実装
 * デフォルトでは特に何もしない。
 */
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
