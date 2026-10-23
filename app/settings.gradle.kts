pluginManagement {
    repositories {
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
        google()
        mavenCentral()
    }
}

// NOTE: must stay "app" — single-module project rooted at app/, and CI expects the debug
// artifact at app/build/outputs/apk/debug/app-debug.apk (AGP names it <rootProject.name>-debug.apk).
rootProject.name = "app"
