pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.momirealms.net/releases/")
    }
}

rootProject.name = "betterend-bukkit"
include(":common", ":nms", ":plugin")
