// Top-level build file. Plugins are declared here with `apply false` so that the
// versions resolved in gradle/libs.versions.toml are shared by every module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
