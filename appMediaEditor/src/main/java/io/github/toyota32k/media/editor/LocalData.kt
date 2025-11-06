package io.github.toyota32k.media.editor

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import io.github.toyota32k.lib.media.editor.model.AmeGlobal
import io.github.toyota32k.utils.android.SharedPreferenceDelegate

class LocalData(val application: Application) {
    private var spd = SharedPreferenceDelegate(application)

    private var persistedEditingUri: String? by spd.prefNullable<String?>()

    val contentResolver: ContentResolver
        get() = application.contentResolver

    var editingUri: Uri?
        set(value) {
            val oldValue = persistedEditingUri
            if (oldValue == value?.toString()) return
            if (oldValue!=null) {
                try {
                    contentResolver.releasePersistableUriPermission(oldValue.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e:Throwable) {
                    AmeGlobal.logger.error(e)
                }
            }
            if (value!=null) {
                contentResolver.takePersistableUriPermission(value, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            persistedEditingUri = value?.toString()
        }
        get() = persistedEditingUri?.toUri()

    var serializedChapters: String? by spd.prefNullable<String?>()
    var serializedCropParams: String? by spd.prefNullable<String?>()
    var playPosition: Long by spd.pref(0L)
    var isPlaying: Boolean by spd.pref(false)
}