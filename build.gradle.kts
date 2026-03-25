val hasAndroidApp = project.findProject(":app") != null
val hasJvmFallback = project.findProject(":test-runner") != null

if (!hasAndroidApp && hasJvmFallback) {
    tasks.register("lint") {
        group = "verification"
        description = "Runs JVM fallback checks when Android SDK is unavailable."
        dependsOn(":test-runner:test")
    }

    tasks.register("build") {
        group = "build"
        description = "Builds JVM fallback project when Android SDK is unavailable."
        dependsOn(":test-runner:build")
    }
}
