// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // room compiler で使用。
    // ksp のバージョンは、kotlin_version と同じ世代のものを指定する必要がある。
    // https://github.com/google/ksp/releases で確認する。
    alias(libs.plugins.devtool.ksp) apply false
}