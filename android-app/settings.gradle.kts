pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MiTelcelWidget"

// :core = lógica pura (red + parser), sin dependencias de Android -> se prueba con JUnit en la JVM.
// :app  = todo lo que depende de Android (widget, Keystore, WorkManager, pantalla de configuración).
include(":core", ":app")
