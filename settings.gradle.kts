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
        // کتابخانه‌ی Tesseract4Android (OCR) از JitPack منتشر می‌شود
        maven("https://jitpack.io")
    }
}

rootProject.name = "PdfKhan"
include(":app")
