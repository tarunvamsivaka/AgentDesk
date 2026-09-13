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

rootProject.name = "AgentDesk"

include(":app")
include(":core:common")
include(":core:persistence")
include(":core:security")
include(":core:settings")
include(":core:device")
include(":agent")
include(":tools")
include(":knowledge")
include(":feature:onboarding")
include(":feature:chat")
include(":feature:library")
include(":feature:health")
include(":feature:settings")
