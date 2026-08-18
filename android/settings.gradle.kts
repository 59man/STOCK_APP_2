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

rootProject.name = "StockTracker"

include(":app")
include(":core:model")
include(":core:calc")
include(":core:database")
include(":core:network")
include(":core:import")
include(":core:data")
include(":core:designsystem")
include(":feature:portfolio")
include(":feature:settings")
