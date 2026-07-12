package io.github.toyota32k.media.editor.view

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.net.toUri
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.toyota32k.binder.Binder
import io.github.toyota32k.binder.RecyclerViewBinding
import io.github.toyota32k.binder.clickBinding
import io.github.toyota32k.binder.command.LiteUnitCommand
import io.github.toyota32k.binder.command.bindCommand
import io.github.toyota32k.binder.editTextBinding
import io.github.toyota32k.binder.headlessBinding
import io.github.toyota32k.binder.list.ObservableList
import io.github.toyota32k.binder.recyclerViewBindingEx
import io.github.toyota32k.binder.visibilityBinding
import io.github.toyota32k.dialog.task.UtImmortalTask
import io.github.toyota32k.dialog.task.showConfirmMessageBox
import io.github.toyota32k.dialog.task.showYesNoMessageBox
import io.github.toyota32k.dialog.task.withActivity
import io.github.toyota32k.lib.media.editor.dialog.NameDialog
import io.github.toyota32k.lib.media.editor.handler.FileUtil
import io.github.toyota32k.logger.UtLog
import io.github.toyota32k.media.editor.MainActivity
import io.github.toyota32k.media.editor.MainActivity.MediaSource.Companion.getType
import io.github.toyota32k.media.editor.databinding.ItemProjectBinding
import io.github.toyota32k.media.editor.databinding.PanelProjectListBinding
import io.github.toyota32k.media.editor.dialog.DetailMessageDialog
import io.github.toyota32k.media.editor.project.Project
import io.github.toyota32k.media.editor.project.ProjectDB
import io.github.toyota32k.media.lib.io.toAndroidFile
import io.github.toyota32k.media.lib.processor.Analyzer
import io.github.toyota32k.utils.android.toUtFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ProjectListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    companion object {
        val logger = UtLog("ProjView")
    }
    class ViewModel(val projectDb: ProjectDB, val application: Application, val saveCurrentProject:suspend ()->Project?)  {
        val currentProject: StateFlow<Project?> = MutableStateFlow<Project?>(null)
        val projectList = ObservableList<Project>()
        val currentProjectName = MutableStateFlow<String>("")

        suspend fun restoreSelection(projectId: Int): Project? {
            projectList.replace(withContext(Dispatchers.IO) { projectDb.getProjectList() })
            val proj = withContext(Dispatchers.IO) { projectDb.getProject(projectId) }
            setCurrentProject(proj)
            return proj
        }

        // プロジェクト追加
        val commandAddProject = LiteUnitCommand {
            UtImmortalTask.launchTask {
                val uri = withActivity<MainActivity, Uri?> { activity ->
                    activity.activityBrokers.openFilePicker.selectFile(arrayOf("video/*", "image/*"))
                } ?: return@launchTask
                if (!addProjectWithMediaUri(uri)) {
                    showConfirmMessageBox("Error", "Cannot create project.")
                }
            }
        }

        suspend fun addProjectWithMediaUri(uri:Uri) : Boolean {
            saveCurrentProject()
            val project = withContext(Dispatchers.IO) {
                projectDb.getProject(uri)
            }
            if (project != null) {
                // 既存プロジェクト
                setCurrentProject(project)
            } else {
                val file = uri.toAndroidFile(application)
                val name = FileUtil.getBaseName(file).takeIf { !it.isNullOrBlank() } ?: "noname"
                val projectName = NameDialog.show(name, "New Project", "Project Name") ?: return false
                val project = withContext(Dispatchers.IO) {
                    projectDb.registerProject(
                        projectName,
                        uri,
                        file.getType() ?: "mp4",
                        null, null, 0
                    )
                } ?: return false
                withContext(Dispatchers.Main) { projectList.add(project) }
                setCurrentProject(project)
            }
            return true
        }

        // プロジェクトのプロパティ表示
        val commandShowProjectInfo = LiteUnitCommand {
            val project = currentProject.value ?: return@LiteUnitCommand
            val name = project.name
            val message = if (project.copied) {
                "${project.sourceUri}\n${project.copiedUri}"
            } else {
                project.sourceUri
            }
            val file = project.editingUri.toUri().toAndroidFile(application)
            UtImmortalTask.launchTask("commandShowProjectInfo") {
                if (project.type == "mp4") {
                    DetailMessageDialog.showMessage(name, message, null, null) { Analyzer.analyze(file).toString() }
                } else {
                    DetailMessageDialog.showNoDetailMessage(name, message, null, null)
                }
            }
        }

        val commandSetProjectName = LiteUnitCommand {
            CoroutineScope(Dispatchers.IO).launch {
                mutex.withLock {
                    val oldProject = currentProject.value ?: return@withLock
                    val project = oldProject.modified(name = currentProjectName.value) ?: return@withLock // not modified
                    projectDb.updateProject(project)
                    setCurrentProject(project)
                    projectList.replaceAll { if (it.id == project.id) project else it }
                }
            }
        }

        /**
         * リストビューからプロジェクトが選択された時の処理
         * カレントを保存してからプロジェクトを切り替える
         */
        private val mutex = Mutex()
        fun setCurrentProject(project: Project?) {
            if (project?.id != currentProject.value?.id) {
                CoroutineScope(Dispatchers.IO).launch {
                    mutex.withLock {
                        saveCurrentProject()
                        (currentProject as MutableStateFlow).value = project
                        val newName = project?.name ?: ""
                        if (newName != currentProjectName.value) {
                            currentProjectName.value = newName
                        }
                    }
                }
            }
        }

        /**
         * カレントプロジェクトの名前変更
         */
//         fun updateProjectName(newName:String) {
//            CoroutineScope(Dispatchers.IO).launch {
//                mutex.withLock {
//                    val oldProject = currentProject.value ?: return@withLock
//                    val project = oldProject.modified(name = newName) ?: return@withLock // not modified
//                    projectDb.updateProject(project)
//                    setCurrentProject(project)
//                    projectList.replaceAll { if (it.documentId == project.documentId) project else it }
//                }
//            }
//        }


        fun onDeletingItem(item:Project): RecyclerViewBinding.IDeletion {
            // カレントプロジェクトは削除禁止
//            if (item == currentProject) return null
            // 削除予約リスト（toBeDeleted）に積む
            // 実際の削除は、complete()で実行。
            logger.debug("deleting $item")
            val indexRemoved = if (item == currentProject.value) {
                projectList.indexOf(item)
            } else -1

            return object:RecyclerViewBinding.IDeletion {
                override fun commit() {
                    logger.debug("deleted $item")
                    CoroutineScope(Dispatchers.IO).launch {
                        mutex.withLock {
                            if (indexRemoved >= 0) {
                                val nextProj = if (projectList.isEmpty()) {
                                    null
                                } else {
                                    projectList[indexRemoved.coerceAtMost(projectList.size - 1)]
                                }
                                setCurrentProject(nextProj)
                            }
                            val copiedUri = item.copiedUri
                            if (copiedUri!=null && projectDb.isDirty(item)) {
                                // アプリのデータストレージ内で編集されたファイルがあれば、データが失われる前に、保存するチャンスを与える
                                UtImmortalTask.awaitTaskCatching("confirmSave", true) {
                                    if (showYesNoMessageBox("Deleting Project", "Editing file is unsaved. Do you want to save it before deleting?")) {
                                        withActivity<MainActivity,Unit> { activity->
                                            val mimeType = when(item.type) {
                                                "mp4" -> "video/mp4"
                                                "png" -> "image/png"
                                                "jpeg", "jpg" -> "image/jpeg"
                                                "gif" -> "image/gif"
                                                else -> "*/*"
                                            }
                                            val outUri = activity.activityBrokers.createFilePicker.selectFile(item.name, mimeType)
                                            if (outUri != null) {
                                                val inFile = copiedUri.toUri().toUtFile()
                                                outUri.toUtFile().copyFrom(inFile)
                                                logger.debug("Saved unsaved edits to $outUri")
                                            }
                                        }
                                    }
                                }
                            }
                            projectDb.unregisterProject(item)
                        }
                    }
                }
            }
        }

    }

    val controls = PanelProjectListBinding.inflate(LayoutInflater.from(context), this, true)
    lateinit var viewModel: ViewModel

    val dateFormat = SimpleDateFormat("yyyy.MM.dd-HH:mm:ss",Locale.US).apply { timeZone = TimeZone.getTimeZone(ZoneId.systemDefault()) }
    fun formatTimestamp(time:Long):String {
        return dateFormat.format(Date(time))
    }

    fun bindViewModel(viewModel:ViewModel, binder: Binder) {
        this.viewModel = viewModel
        controls.projectList.addItemDecoration(DividerItemDecoration(context, LinearLayoutManager(context).orientation))

        binder
            .editTextBinding(controls.projectNameEdit, viewModel.currentProjectName)
            .bindCommand(viewModel.commandAddProject, controls.addProjectButton)
            .bindCommand(viewModel.commandShowProjectInfo, controls.projectInfoButton)
            .visibilityBinding(controls.projectInfoButton, viewModel.currentProject.map { it!=null })
            .bindCommand(viewModel.commandSetProjectName, controls.projectNameEdit)
            .recyclerViewBindingEx<Project, ItemProjectBinding>(controls.projectList) {
                options(
                    list = viewModel.projectList,
                    inflater = ItemProjectBinding::inflate,
                    bindView = { views, itemBinder, _, item ->
                        views.root.isSelected = item == viewModel.currentProject.value
                        views.nameText.text = item.name
//                        views.urlText.text = item.uri
                        views.subText.text = formatTimestamp(item.fileTimestamp)
                        if (item.isVideo) {
                            views.typePhotoIcon.visibility = View.GONE
                            views.typeVideoIcon.visibility = View.VISIBLE
                        } else {
                            views.typePhotoIcon.visibility = View.VISIBLE
                            views.typeVideoIcon.visibility = View.GONE
                        }
                        itemBinder
                            .owner(binder.requireOwner)
                            .clickBinding(views.root) {
                                viewModel.setCurrentProject(item)
                            }
                            .headlessBinding(viewModel.currentProject) {
                                val sel = it == item
                                views.root.isSelected = sel
                            }
                    },
                    autoScroll = RecyclerViewBinding.AutoScrollMode.NONE,
                    gestureParams = RecyclerViewBinding.GestureParams(swipeToDelete = true, dragToMove = false, deletionHandler = RecyclerViewBinding.SimpleDeletionHandler<Project>(viewModel::onDeletingItem))
                )
            }
    }
}