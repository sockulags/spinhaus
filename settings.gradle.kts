pluginManagement {
    repositories {
        if (System.getenv("SPINHAUS_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        if (System.getenv("SPINHAUS_CORE_ONLY") == null) google()
        mavenCentral()
    }
}

rootProject.name = "spinhaus"

include(":core")
// The Android app module needs the Android SDK. Set SPINHAUS_CORE_ONLY=1 to build/test only :core.
if (System.getenv("SPINHAUS_CORE_ONLY") == null) {
    include(":app")
}
