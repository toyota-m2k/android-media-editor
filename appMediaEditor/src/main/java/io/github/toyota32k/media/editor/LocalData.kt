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

    var editingUri: String? by spd.prefNullable<String?>()
    var projectName: String? by spd.prefNullable<String?>()
    var serializedChapters: String? by spd.prefNullable<String?>()
    var serializedCropParams: String? by spd.prefNullable<String?>()
    var playPosition: Long by spd.pref(0L)
    var isPlaying: Boolean by spd.pref(false)
}