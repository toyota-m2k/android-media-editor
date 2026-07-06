package io.github.toyota32k.lib.media.editor.dialog

import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import io.github.toyota32k.binder.BindingMode
import io.github.toyota32k.binder.IIDValueResolver
import io.github.toyota32k.binder.VisibilityBinding
import io.github.toyota32k.binder.checkBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.radioGroupBinding
import io.github.toyota32k.binder.sliderBinding
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtAndroidViewModel
import io.github.toyota32k.dialog.task.UtAndroidViewModel.Companion.createAndroidViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.dialog.task.launchSubTask
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.lib.media.editor.databinding.DialogSelectQualityBinding
import io.github.toyota32k.lib.media.editor.model.TrialConvertHelper
import io.github.toyota32k.media.lib.strategy.IVideoStrategy
import io.github.toyota32k.media.lib.strategy.PresetAudioStrategies
import io.github.toyota32k.media.lib.strategy.PresetVideoStrategies
import io.github.toyota32k.media.lib.strategy.PresetVideoStrategies.isValid
import io.github.toyota32k.utils.TimeSpan
import io.github.toyota32k.utils.lifecycle.ConstantLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

//object NoReEncodeStrategy : IVideoStrategy {
//    override val sizeCriteria: VideoStrategy.SizeCriteria
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val bitRate: MaxDefault
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val frameRate: MaxDefault
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val iFrameInterval: MinDefault
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val colorFormat: ColorFormat
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val bitRateMode: BitRateMode
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val encoderType: VideoStrategy.EncoderType
//        get() = throw UnsupportedOperationException("empty implementation")
//
//    override fun createOutputFormat(inputFormat: MediaFormat, metaData: MetaData, encoder: MediaCodec, renderOption: RenderOption): MediaFormat {
//        throw UnsupportedOperationException("empty implementation")
//    }
//
//    override fun derived(
//        codec: Codec,
//        profile: Profile,
//        level: Level?,
//        fallbackProfiles: Array<ProfileLv>?,
//        sizeCriteria: VideoStrategy.SizeCriteria,
//        bitRate: MaxDefault,
//        frameRate: MaxDefault,
//        iFrameInterval: MinDefault,
//        colorFormat: ColorFormat?,
//        bitRateMode: BitRateMode?,
//        encoderType: VideoStrategy.EncoderType
//    ): IVideoStrategy {
//        throw UnsupportedOperationException("empty implementation")
//    }
//
//    override val name: String
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val codec: Codec
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val profile: Profile
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val maxLevel: Level
//        get() = throw UnsupportedOperationException("empty implementation")
//    override val fallbackProfiles: Array<ProfileLv>
//        get() = throw UnsupportedOperationException("empty implementation")
//
//    override fun createEncoder(): MediaCodec {
//        throw UnsupportedOperationException("empty implementation")
//    }
//}

class SelectQualityDialog : UtDialogEx() {
    enum class VideoQuality(@param:IdRes val id: Int, @param:StringRes val strId:Int, val strategy: IVideoStrategy) {
        Highest(R.id.radio_highest, R.string.highest_quality, PresetVideoStrategies.InvalidStrategy),
        High(R.id.radio_high, R.string.high_quality, PresetVideoStrategies.HEVC1080LowProfile),
        Middle(R.id.radio_middle, R.string.middle_quality,PresetVideoStrategies.HEVC720Profile),
        Low(R.id.radio_low, R.string.low_quality,PresetVideoStrategies.HEVC720LowProfile);
        companion object {
            fun valueOf(@IdRes id: Int): VideoQuality? {
                return VideoQuality.entries.find { it.id == id }
            }
        }

        object IDResolver : IIDValueResolver<VideoQuality> {
            override fun id2value(id: Int): VideoQuality? = valueOf(id)
            override fun value2id(v: VideoQuality): Int = v.id
        }

        fun estimateSize(duration:Long):Long? {
            return if (strategy.isValid) (strategy.bitRate.max + PresetAudioStrategies.AACDefault.bitRatePerChannel.max) * duration / 8000 else null // bytes
        }
    }

    class QualityViewModel(application: Application) : UtAndroidViewModel(application) {
        private class TrialCache {
            private data class TrialType(val quality: VideoQuality, val keepHdr:Boolean, val brightnessIndex:Int) {
                companion object {
                    fun typeOf(vq: VideoQuality, keepHdr:Boolean, brightnessIndex:Int):TrialType {
                        return TrialType(vq, keepHdr, brightnessIndex)
                    }
                }
            }
            private val map = mutableMapOf<TrialType, File>()

            fun fileNameOf(vq: VideoQuality, keepHdr:Boolean, brightnessIndex: Int):String {
                return "${vq.name}_${if(keepHdr) "1" else "0"}_${brightnessIndex}"
            }

            fun get(vq: VideoQuality, keepHdr:Boolean, brightnessIndex: Int):File? {
                val type = TrialType.typeOf(vq, keepHdr, brightnessIndex)
                val cached = map[type]
                if (cached!=null && !cached.exists()) {
                    map.remove(type)
                    return null
                }
                return cached
            }

            private fun File.safeDelete() {
                try {
                    if (exists()) {
                        delete()
                    }
                } catch (_:Throwable) {}
            }

            fun put(vq: VideoQuality, keepHdr:Boolean, brightnessIndex:Int, file:File) {
                val type = TrialType.typeOf(vq, keepHdr, brightnessIndex) ?: return
                val old = map[type]
                if (old!=null && old!=file) {
                    old.safeDelete()
                }
                map[type] = file
            }
            fun clear(application: Application?) {
                map.values.forEach {
                    it.safeDelete()
                }
                map.clear()
            }
        }

        lateinit var trialConvertHelper: TrialConvertHelper private set
        lateinit var durationText:String private set

        var convertFrom:Long = 0L
        val quality = MutableStateFlow(VideoQuality.High)
        val sourceHdr = MutableStateFlow(false)
        val keepHdr = MutableStateFlow(true)
        val brightnessIndex = MutableStateFlow(0f)
        private val trialCache = TrialCache()

        val brightness:Float get() {
            val index = brightnessIndex.value.roundToInt()
            return if (index == 0) 1f
            else 10f.pow(index.toFloat()*3f/100f)
        }
        val estimatedSizes = mapOf<VideoQuality, MutableStateFlow<Long>>(
            VideoQuality.Highest to MutableStateFlow(0L),
            VideoQuality.High to MutableStateFlow(0L),
            VideoQuality.Middle to MutableStateFlow(0L),
            VideoQuality.Low to MutableStateFlow(0L),
        )

        fun setConvertHelper(helper:TrialConvertHelper) {
            trialConvertHelper = helper
            val trimmedDuration = trialConvertHelper.trimmedDuration
            estimatedSizes.forEach { (quality, flow) ->
                flow.value = quality.estimateSize(trimmedDuration) ?: (trialConvertHelper.inputFile.getLength() * trialConvertHelper.trimmedDuration / trialConvertHelper.durationMs)
            }
            durationText = TimeSpan(trimmedDuration).formatAuto()
        }

        override fun onCleared() {
            trialCache.clear(getApplication())
            super.onCleared()
        }

        /**
         * 変換を試みる。
         * 変換に成功したら変換後のファイルを返す。
         * 変換に失敗したらnullを返す。
         */
        private suspend fun tryConvert(brightness:Float, brightnessIndex: Int):File? {
            val cached = trialCache.get(quality.value, keepHdr.value, brightnessIndex)
            if (cached!=null) {
                return cached
            }
            trialConvertHelper.trimFileName = trialCache.fileNameOf(quality.value, keepHdr.value, brightnessIndex) ?: return null
            trialConvertHelper.keepHdr = keepHdr.value && sourceHdr.value
            trialConvertHelper.videoStrategy = quality.value.strategy
            return trialConvertHelper.tryConvert(getApplication(), convertFrom, 10.seconds.inWholeMilliseconds, brightness)?.apply {
                trialCache.put(quality.value, keepHdr.value, brightnessIndex, this)
                val report = trialConvertHelper.report
                if (trialConvertHelper.result.succeeded && report!=null) {
                    var bitRate = report.output.videoSummary?.bitRate
                    if (bitRate!=null && bitRate > 0) {
                        val audioBitRate = report.output.audioSummary?.bitRate
                        if (audioBitRate != null && audioBitRate > 0) {
                            bitRate += audioBitRate
                        }
                        estimatedSizes[quality.value]?.value = bitRate * trialConvertHelper.trimmedDuration / 8000
                    }
                }
            }
        }

        val testCommand = LiteUnitCommand {
            if (!quality.value.strategy.isValid) return@LiteUnitCommand
            immortalTaskContext.launchSubTask {
                val workFile:File? = tryConvert(brightness, brightnessIndex.value.roundToInt())
                if (workFile!=null) {
                    VideoPreviewDialog.show(workFile.toUri().toString(), "preview")
                }
            }
        }
    }

    val viewModel by lazy { getViewModel<QualityViewModel>() }
    lateinit var controls: DialogSelectQualityBinding

    override fun preCreateBodyView() {
        cancellable = false
        leftButtonType = ButtonType.CANCEL
        rightButtonType = ButtonType.DONE
        optionButtonType = ButtonType("Try", true)
        optionButtonWithAccent = true
        gravityOption = GravityOption.CENTER
        widthOption = WidthOption.LIMIT(400)
        heightOption = HeightOption.COMPACT
        title = requireActivity().getString(R.string.video_quality)
        enableFocusManagement()
            .setInitialFocus(R.id.radio_high)
    }

    fun caFormatSize(bytes:Long):String {
        if(bytes>1000*1000*1000) {
            val m = bytes / (1000*1000)
            return "ca ${m/1000f} GB"
        } else if(bytes>1000*1000) {
            val k = bytes / 1000
            return "ca ${k/1000} MB"
        } else if(bytes>1000) {
            return "ca ${bytes/1000} KB"
        } else {
            return "$bytes B"
        }
    }


    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogSelectQualityBinding.inflate(inflater.layoutInflater)
        return controls.root.also { _ ->
            binder
                .radioGroupBinding(controls.qualityGroup, viewModel.quality, VideoQuality.IDResolver)
                .checkBinding(controls.checkKeepHdr, viewModel.keepHdr)
                .visibilityBinding(controls.convertHdrGroup, viewModel.sourceHdr, hiddenMode = VisibilityBinding.HiddenMode.HideByGone)
                .textBinding(controls.durationText, ConstantLiveData(viewModel.durationText))
                .textBinding(controls.radioHighest, viewModel.estimatedSizes[VideoQuality.Highest]!!.map {"${getString(VideoQuality.Highest.strId)} ${caFormatSize(it)}"})
                .textBinding(controls.radioHigh, viewModel.estimatedSizes[VideoQuality.High]!!.map {"${getString(VideoQuality.High.strId)}       ${caFormatSize(it)}"})
                .textBinding(controls.radioMiddle, viewModel.estimatedSizes[VideoQuality.Middle]!!.map {"${getString(VideoQuality.Middle.strId)}       ${caFormatSize(it)}"})
                .textBinding(controls.radioLow, viewModel.estimatedSizes[VideoQuality.Low]!!.map {"${getString(VideoQuality.Low.strId)}       ${caFormatSize(it)}"})
                .dialogOptionButtonCommand(viewModel.testCommand)
                .dialogOptionButtonEnable(viewModel.quality.map { it.strategy.isValid })
                .sliderBinding(controls.sliderBrightness, viewModel.brightnessIndex,BindingMode.TwoWay)
        }
    }

    companion object {
        data class Result(val quality: VideoQuality, val keepHdr: Boolean, val brightness:Float)
        suspend fun show(hdr:Boolean, helper:TrialConvertHelper, pos:Long):Result? {
            return UtImmortalTask.awaitTaskResult(this::class.java.name) {
                val vm = createAndroidViewModel<QualityViewModel>().apply {
                    setConvertHelper(helper)
                    sourceHdr.value = hdr
                    convertFrom = pos
                }
                if(showDialog(this.taskName) { SelectQualityDialog() }.status.positive) {
                    Result(vm.quality.value, vm.keepHdr.value, vm.brightness)
                } else null
            }
        }
    }
}