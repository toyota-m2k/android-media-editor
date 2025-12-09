package io.github.toyota32k.media.editor.dialog

import android.os.Bundle
import android.view.View
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.toyota32k.binder.RecyclerViewBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.headlessBinding
import io.github.toyota32k.binder.list.ObservableList
import io.github.toyota32k.binder.onGlobalLayout
import io.github.toyota32k.binder.recyclerViewBindingEx
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.media.editor.databinding.DialogProjectsManagerBinding
import io.github.toyota32k.media.editor.databinding.ItemProjectBinding
import io.github.toyota32k.media.editor.project.Project
import io.github.toyota32k.media.editor.project.ProjectDB
import io.github.toyota32k.utils.android.setLayoutHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

class ProjectManagerDialog : UtDialogEx() {
    data class ProjectSelection(val selectedProject:Project?, val removeCurrentProject:Boolean)
    class ProjectManagerViewModel : UtDialogViewModel() {
        lateinit var projectDb: ProjectDB
        var currentProject: Project? = null
        val projectList = ObservableList<Project>()
        var selected = MutableStateFlow<Project?>(null)
//        val itemSelected: LiteCommand<Project> = LiteCommand { selected = it }
        val toBeDeleted = mutableListOf<Project>()
        var result: ProjectSelection? = null

        fun initWithDB(projectDb: ProjectDB, currentProject: Project?, list:List<Project>): ProjectManagerViewModel = apply {
            this.projectDb = projectDb
            this.currentProject = currentProject
            this.selected.value = currentProject
            this.projectList.addAll(list)
        }

        fun onDeletingItem(item:Project): RecyclerViewBinding.IDeletion {
            // カレントプロジェクトは削除禁止
//            if (item == currentProject) return null
            // 削除予約リスト（toBeDeleted）に積む
            // 実際の削除は、complete()で実行。
            return object:RecyclerViewBinding.IDeletion {
                override fun commit() {
                    logger.debug("deleting $item")
                    toBeDeleted.add(item)
                    if (item==selected.value) {
                        selected.value = null
                    }
                    if (projectList.isEmpty()) {
                        allItemsDeleted.value = true
                    }
                }
            }
        }

        fun selectItem(item:Project) {
            selected.value = item
        }

        val allItemsDeleted = MutableStateFlow<Boolean>(false)

        suspend fun complete(new:Boolean=false) {
            var deleteCurrent = false
            if (toBeDeleted.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    for (item in toBeDeleted) {
                        if (item == currentProject) {
                            deleteCurrent = true
                        } else {
                            projectDb.unregisterProject(item)
                        }
                    }
                }
            }
            toBeDeleted.clear()
            result = ProjectSelection(if(!new) selected.value else null, deleteCurrent)
        }
    }
    override fun preCreateBodyView() {
        title="Projects"
        heightOption = HeightOption.CUSTOM
        widthOption = WidthOption.LIMIT(500)
        //leftButtonType = ButtonType.CANCEL
        rightButtonType = ButtonType.CANCEL
        cancellable = false
        setOptionButton("New")
        enableFocusManagement().autoRegister()
    }

    val viewModel by lazy { getViewModel<ProjectManagerViewModel>() }
    lateinit var controls : DialogProjectsManagerBinding

    val dateFormat = SimpleDateFormat("yyyy.MM.dd-HH:mm:ss",Locale.US).apply { timeZone = TimeZone.getTimeZone(ZoneId.systemDefault()) }
    private fun formatTimestamp(time:Long):String {
        return dateFormat.format(Date(time))
    }

    private var mListItemHeight:Int = 0
    private fun setListItemHeight(height:Int) {
        if (mListItemHeight!=height) {
            mListItemHeight = height
            updateCustomHeight()
        }
    }

    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogProjectsManagerBinding.inflate(inflater.layoutInflater, null, false)
        binder
//            .dialogRightButtonEnable(viewModel.selected.map { it != null && it!=viewModel.currentProject})
            .recyclerViewBindingEx<Project, ItemProjectBinding>(controls.projectList) {
                options(
                    list = viewModel.projectList,
                    inflater = ItemProjectBinding::inflate,
                    bindView = { views, itemBinder, _, item ->
                        views.root.isSelected = item == viewModel.selected.value
                        views.nameText.text = item.name
                        views.subText.text = formatTimestamp(item.fileTimestamp)
                        if (item.isVideo) {
                            views.typePhotoIcon.visibility = View.GONE
                            views.typeVideoIcon.visibility = View.VISIBLE
                        } else {
                            views.typePhotoIcon.visibility = View.VISIBLE
                            views.typeVideoIcon.visibility = View.GONE
                        }
//                        views.trashButton.setOnClickListener {
//                            viewModel.onDeletingItem(item).commit()
//                        }
                        views.root.setOnClickListener {
                            viewModel.selectItem(item)
                            AmeGlobal.logger.debug("PMD:${item.id} selected")
                            lifecycleScope.launch {
                                viewModel.complete(false)
                                super.onPositive()
                            }
                        }
                        itemBinder
                            .owner(this@ProjectManagerDialog)
                            .headlessBinding(viewModel.selected) {
                                val sel = it == item
                                views.root.isSelected = sel
                                AmeGlobal.logger.debug("PMD:${item.id} selected=$sel ${item.name}")
                            }
                    },
                    autoScroll = RecyclerViewBinding.AutoScrollMode.NONE,
                    gestureParams = RecyclerViewBinding.GestureParams(swipeToDelete = true, dragToMove = false, deletionHandler = viewModel::onDeletingItem)
                )
            }
            .dialogOptionButtonCommand(LiteUnitCommand {
                lifecycleScope.launch {
                    viewModel.complete(true)
                    super.onPositive()
                }
            })
            .headlessBinding(viewModel.allItemsDeleted) {
                if (it==true) {
                    onPositive()
                }
            }
            .add {
                viewModel.projectList.addListener(binder.requireOwner) {
                    updateCustomHeight()
                }
            }
            .onGlobalLayout(controls.projectList) {
                val recyclerView = controls.projectList
                val count = recyclerView.childCount
                if (count>0) {
                    val child = recyclerView.getChildAt(0)
                    val height = child.measuredHeight
                    if (height>0) {
                        setListItemHeight(height)
                        return@onGlobalLayout false // これ以上のイベント通知は不要
                    }
                }
                true
            }

        return controls.root
    }

    override fun calcCustomContainerHeight(currentBodyHeight: Int, currentContainerHeight: Int, maxContainerHeight: Int): Int {
        val recyclerView = controls.projectList

        val adapter = recyclerView.adapter ?: return 0
        val calculatedRvHeight = max(mListItemHeight * viewModel.projectList.size,300)
        val remainHeight = currentBodyHeight-recyclerView.height    // == listviewを除く、その他のパーツの高さ合計
        val maxLvHeight = maxContainerHeight - remainHeight     // listViewの最大高さ
        return if(calculatedRvHeight>=maxLvHeight) {
            // リストビューの中身が、最大高さを越える --> 最大高さを採用
            recyclerView.setLayoutHeight(maxLvHeight)
            maxContainerHeight
        } else {
            // リストビューの中身が、最大高さより小さい --> リストビューの中身のサイズを採用
            recyclerView.setLayoutHeight(calculatedRvHeight)
            calculatedRvHeight + remainHeight
        }



//        if (recyclerView.childCount > 0) {
//            val avgHeight = totalHeight / recyclerView.childCount
//            totalHeight = avgHeight * itemCount
//        }

    }

    override fun onPositive() {
        lifecycleScope.launch {
            viewModel.complete(false)
            super.onPositive()
        }
    }

    companion object {
        suspend fun show(projectDb: ProjectDB, currentTargetUri: String?):ProjectSelection? {
            return UtImmortalTask.awaitTaskResult(this::class.java.name) {
                val (currentProject,list) = withContext(Dispatchers.IO) {
                    Pair(if (currentTargetUri!=null) projectDb.getProject(currentTargetUri.toUri()) else null,
                    projectDb.getProjectList())
                }
                if (list.isEmpty()) {
                    ProjectSelection(null, false)
                } else {
                    val vm = createViewModel<ProjectManagerViewModel> { initWithDB(projectDb, currentProject, list) }
                    if (showDialog(taskName) { ProjectManagerDialog() }.status.ok) {
                        vm.result
                    } else null
                }
            }
        }
    }
}