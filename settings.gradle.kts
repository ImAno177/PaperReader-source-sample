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

rootProject.name = "PaperReaderSourceSample"
include(":app")

val paperReaderSdkPath = providers.gradleProperty("paperReaderSdkPath").orNull ?: "PaperReader"
includeBuild(file(paperReaderSdkPath))
