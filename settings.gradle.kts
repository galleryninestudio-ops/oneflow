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

rootProject.name = "oneflow"

include(":core:contracts")
include(":core:kernel")
include(":core:models")
include(":core:intelligence")
include(":execution:broker")
include(":execution:flowcode")
include(":capabilities:files")
include(":capabilities:development")
include(":capabilities:research")
include(":capabilities:computer-use")
include(":capabilities:builder")
include(":platform:runtime")
include(":app")
