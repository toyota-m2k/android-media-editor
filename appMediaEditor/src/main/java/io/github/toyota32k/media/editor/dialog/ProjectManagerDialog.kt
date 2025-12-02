package io.github.toyota32k.media.editor.dialog

import android.os.Bundle
import android.view.View
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.github.toyota32k.binder.RecyclerViewBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.headlessBinding
import io.github.toyota32k.binder.list.ObservableList
import io.github.toyota32k.binder.recyclerViewBindingEx
import io.github.toyota32k.dialog.UtDialogEx
import io.github.toyota32k.dialog.task.UtDialogViewModel
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.createViewModel
import io.github.toyota32k.dialog.task.getViewModel
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.lib.player.TpLib
import io.github.toyota32k.media.editor.databinding.DialogProjectsManagerBinding
import io.github.toyota32k.media.editor.databinding.ItemProjectBinding
import io.github.toyota32k.media.editor.project.Project
import io.github.toyota32k.media.editor.project.ProjectDB
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

class ProjectManagerDialog : UtDialogEx() {
    data class ProjectSelection(val project:Project?)
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

        fun onDeletingItem(item:Project): RecyclerViewBinding.IDeletion? {
            // カレントプロジェクトは削除禁止
            if (item == currentProject) return null
            // 削除予約リスト（toBeDeleted）に積む
            // 実際の削除は、complete()で実行。
            return object:RecyclerViewBinding.IDeletion {
                override fun commit() {
                    TpLib.logger.debug("deleting $item")
                    toBeDeleted.add(item)
                }
            }
        }

        fun selectItem(item:Project) {
            selected.value = item
        }

        suspend fun complete(new:Boolean=false) {
            result = ProjectSelection(if(!new) selected.value else null)
            if (toBeDeleted.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    for (item in toBeDeleted) {
                        projectDb.unregisterProject(item)
                    }
                    projectDb.checkPoint()
                }
            }
            toBeDeleted.clear()
        }
    }
    override fun preCreateBodyView() {
        title="Projects"
        heightOption = HeightOption.FULL
        widthOption = WidthOption.LIMIT(400)
        leftButtonType = ButtonType.CANCEL
        rightButtonType = ButtonType.DONE
        setOptionButton("New")
        enableFocusManagement().autoRegister()
    }

    val viewModel by lazy { getViewModel<ProjectManagerViewModel>() }
    lateinit var controls : DialogProjectsManagerBinding

    val dateFormat = SimpleDateFormat("yyyy.MM.dd-HH:mm:ss",Locale.US).apply { timeZone = TimeZone.getTimeZone(ZoneId.systemDefault()) }
    private fun formatTimestamp(time:Long):String {
        return dateFormat.format(Date(time))
    }


    override fun createBodyView(savedInstanceState: Bundle?, inflater: IViewInflater): View {
        controls = DialogProjectsManagerBinding.inflate(inflater.layoutInflater, null, false)
        binder
            .dialogRightButtonEnable(viewModel.selected.map { it != null && it!=viewModel.currentProject})
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
                        views.trashButton.setOnClickListener {
                            viewModel.onDeletingItem(item)?.commit()
                        }
                        views.root.setOnClickListener {
                            viewModel.selectItem(item)
                            AmeGlobal.logger.debug("PMD:${item.id} selected")
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

        return controls.root
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
                    ProjectSelection(null)
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