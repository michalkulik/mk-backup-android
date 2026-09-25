// Root build script. Configuration is kept in :app/build.gradle.kts.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
