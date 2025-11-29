package io.github.toyota32k.lib.media.editor.dialog

import android.os.Bundle
import android.view.View
import io.github.toyota32k.binder.IIDValueResolver
import io.github.toyota32k.binder.checkBinding
import io.github.toyota32k.binder.enableBinding
import io.github.toyota32k.binder.multiEnableBinding
import io.github.toyota32k.binder.radioGroupBinding
import io.github.toyota32k.binder.sliderBinding
import io.github.toyota32k.binder.textBinding
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.UtImmortalTaskBase
import io.github.toyota32k.dialog.task.awaitSubTaskResult
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.R
import io.github.toyota32k.lib.media.editor.databinding.DialogSelectRangeBinding
import io.github.toyota32k.lib.player.model.RangedPlayModel
import io.github.toyota32k.utils.TimeSpan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.coerceIn

data class SliderPartition(
    val enabled:Boolean,
    val duration:Long,
    val span:Long) {
    companion object {
        fun fromModel(model: RangedPlayModel?, duration: Long): SliderPartition {
            return if (model!=null) SliderPartition(true, model.duration, model.spanLength) else create(duration)
        }
        fun create(duration: Long): SliderPartition {
            return SliderPartition(true, duration, 3)
        }
    }
    fun toModel(): RangedPlayModel? {
        return if(enabled) RangedPlayModel(duration, span) else null
    }
}

class SliderPartitionDialog: UtDialogEx() {
    class SliderPartitionViewModel: UtDialogViewModel() {
        object SpanResolver : IIDValueResolver<Int> {
            override fun id2value(id: Int): Int? {
                return when(id) {
                    R.id.radio_span_3min -> 3
                    R.id.radio_span_5min -> 5
                    R.id.radio_span_10min -> 10
                    else -> 0
                }
            }

            override fun value2id(v: Int): Int {
                return when(v) {
                    3 -> R.id.radio_span_3min
                    5 -> R.id.radio_span_5min
                    10 -> R.id.radio_span_10min
                    else -> R.id.radio_span_custom
                }
            }
            fun isCustom(v:Int):Boolean {
                return value2id(v) == R.id.radio_span_custom
            }
        }

        var naturalDuration: Long = 0L
            private set
        val minSpan = 1 // min
        val maxSpan get() = ((naturalDuration - minSpan)/60000).toInt().coerceAtLeast(minSpan+1)
        val enablePartialMode = MutableStateFlow(true)
        val presetSpan = MutableStateFlow(3)
        val customSpan = MutableStateFlow(1f)

        fun initWith(params:SliderPartition?) {
            if (params == null) return
            naturalDuration = params.duration
            enablePartialMode.value = params.enabled
            val spanInMin = (params.span/60000f).toInt()
            val id = SpanResolver.value2id(spanInMin)
            if(id == R.id.radio_span_custom) {
                customSpan.value = spanInMin.toFloat()
            } else {
                presetSpan.value = spanInMin.coerceIn(minSpan, maxSpan)
            }
        }

        fun toSliderPartition(): SliderPartition {
            return SliderPartition(
                enablePartialMode.value,
                naturalDuration,
                if(SpanResolver.isCustom(presetSpan.value)) customSpan.value.roundToLong()*60000 else presetSpan.value*60000L)
        }

        companion object {
            // 1 min
//            fun createBy(task: IUtImmortalTask, currentParams: SplitParams): RangeModeViewModel {
//                return UtImmortalViewModelHelper.createBy(
//                    RangeModeViewModel::class.java,
//                    task
//                ).apply { initWith(currentParams) }
//            }
//
//            fun instanceFor(dlg: SelectRangeDialog): RangeModeViewModel {
//                return UtImmortalViewModelHelper.instanceFor(RangeModeViewModel::class.java, dlg)
//            }
        }
    }

    val viewModel: SliderPartitionViewModel by lazy { getViewModel() }
    lateinit var controls: DialogSelectRangeBinding

    override fun preCreateBodyView() {
        draggable = true
        scrollable = true
        heightOption = HeightOption.AUTO_SCROLL
        widthOption = WidthOption.LIMIT(400)
        gravityOption = GravityOption.CENTER
        leftButtonType = ButtonType.CANCEL
        rightButtonType = ButtonType.OK
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        title = "Partial Edit -- ${TimeSpan(viewModel.naturalDuration).formatAuto()}"
        controls = DialogSelectRangeBinding.inflate(inflater.layoutInflater)
        controls.spanSlider.valueTo = viewModel.maxSpan.toFloat()
        controls.spanSlider.valueFrom = viewModel.minSpan.toFloat()
        controls.spanSlider.setLabelFormatter {
            "${it.roundToInt()} min"
        }
        binder
            .checkBinding(controls.checkEnablePartialMode, viewModel.enablePartialMode)
            .multiEnableBinding(arrayOf(controls.radioSpan3min, controls.radioSpan5min, controls.radioSpan10min, controls.radioSpanCustom), viewModel.enablePartialMode)
            .radioGroupBinding(controls.radioSpanSelection, viewModel.presetSpan, SliderPartitionViewModel.SpanResolver)
            .enableBinding(controls.spanSlider, combine(viewModel.enablePartialMode, viewModel.presetSpan) { e, s -> e && s == 0 })
            .textBinding(controls.spanValue, combine(viewModel.presetSpan, viewModel.customSpan) { p, c ->
                if (p == 0) "${c.roundToLong()} min" else "$p min"
            })
            .sliderBinding(controls.spanSlider, viewModel.customSpan)
        return controls.root
    }

    companion object {
        const val MIN_DURATION = 60*2*1000L     // 2分以上ないとSliderが作れない
        suspend fun show(currentParams: SliderPartition?): SliderPartition? {
            return UtImmortalTask.awaitTaskResult(this::class.java.name) {
                val vm = createViewModel<SliderPartitionViewModel> { initWith(currentParams) }
                if(showDialog(taskName) { SliderPartitionDialog() }.status.ok) {
                    vm.toSliderPartition()
                } else {
                    null
                }
            }
        }
    }
}