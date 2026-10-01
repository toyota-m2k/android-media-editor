package io.github.toyota32k.media.editor

import io.github.toyota32k.lib.themes.AbstractThemeSettings
import io.github.toyota32k.lib.themes.BuiltInThemeList
import io.github.toyota32k.lib.themes.ContrastLevel
import io.github.toyota32k.lib.themes.IThemeList
import io.github.toyota32k.lib.themes.NightMode
import io.github.toyota32k.lib.themes.ThemeData
import io.github.toyota32k.utils.UtLib
import io.github.toyota32k.utils.android.SharedPreferenceDelegate

object Settings : AbstractThemeSettings() {
    private val spd : SharedPreferenceDelegate by lazy {
        SharedPreferenceDelegate(UtLib.applicationContext, "settings")
    }
    var themeName:String by spd.pref("Default")
    var contrastLevelName by spd.pref("System")
    var nightModeInt by spd.pref(-1)

    val themeList: IThemeList = BuiltInThemeList
    override var themeData: ThemeData
        get() = themeList.themeOf(themeName)
        set(value) { themeName = value.label }
    override var contrastLevel: ContrastLevel
        get() = ContrastLevel.parse(contrastLevelName) ?: ContrastLevel.System
        set(value) { contrastLevelName = value.name }
    override var dayNightMode: NightMode
        get() = NightMode.ofMode(nightModeInt) ?: NightMode.System
        set(value) { nightModeInt = value.mode }
}