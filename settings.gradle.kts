import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PhairPlay"

fun resolveSdkDir(): String? {
    val localProps = file("local.properties")
    if (localProps.isFile) {
        val props = Properties().apply {
            localProps.inputStream().use(::load)
        }
        val sdkDir = props.getProperty("sdk.dir")?.trim()
        if (!sdkDir.isNullOrEmpty() && file(sdkDir).exists()) return sdkDir
    }

    val envSdkDir = sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it)?.trim() }
        .firstOrNull { it.isNotEmpty() && file(it).exists() }

    return envSdkDir
}

include(":test-runner")
project(":test-runner").projectDir = file("test-runner")

if (resolveSdkDir() != null) {
    include(":app")
} else {
    logger.lifecycle("Android SDK was not found. ':app' is skipped; ':test-runner' remains available for JVM-only checks.")
}
