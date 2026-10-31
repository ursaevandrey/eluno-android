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

rootProject.name = "Eluno"

include(
    ":app",
    ":core:domain",
    ":core:data",
    ":core:database",
    ":core:matter",
    ":core:chip",
    ":core:designsystem",
    ":feature:devices",
    ":feature:commissioning",
    ":feature:settings",
    ":feature:widgets",
)
