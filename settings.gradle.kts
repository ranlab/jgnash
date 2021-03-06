// extract plugin versions from gradle.properties
val testFxVersion: String by settings
val monocleVersion: String by settings

pluginManagement {
    val javafxPluginVersion: String by settings
    val versionsPluginVersion: String by settings

    plugins {
        id("org.openjfx.javafxplugin") version javafxPluginVersion
        id ("com.github.ben-manes.versions") version versionsPluginVersion
    }
}

rootProject.name = "jgnash"

include ("bootloader", "jgnash-bayes", "jgnash-resources", "jgnash-core", "jgnash-convert",
        "jgnash-plugin", "jgnash-fx", "jgnash-report-core", "jgnash-fx-test-plugin", "mt940", "jgnash-tests")
