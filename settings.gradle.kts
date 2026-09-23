pluginManagement {
    repositories {
        // Google's artifact mirror. Listed first because some networks cannot
        // reach maven.google.com directly; it carries the same AGP, androidx
        // and com.google.* artifacts.
        maven {
            url = uri("https://dl.google.com/dl/android/maven2")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("https://dl.google.com/dl/android/maven2")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketAgent"
include(":app")
