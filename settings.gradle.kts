pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy { eachPlugin {
        if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}")
    } }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Fallback for environments where dl.google.com returns 404 for Maven artifacts.
        maven("https://maven.aliyun.com/repository/google") {
            content { includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\..*") }
        }
        google()
    }
}
rootProject.name = "GW6Voice"
include(":core", ":render", ":phone", ":wear")
