dependencies {
    implementation(project(":common"))
    implementation(project(":nms"))
    compileOnly("io.papermc.paper:paper-api:${property("paper_version")}")
    compileOnly("net.momirealms:craft-engine-core:${property("craftengine_version")}")
    compileOnly("net.momirealms:craft-engine-bukkit:${property("craftengine_version")}")
    // UltimateAdvancementAPI is a separate server plugin, never bundled. Vendored as a jar because
    // this is the owner's fork (MC 26.2 NMS, Folia support, the auto-layout registerAdvancements
    // overload); the 2.8.0 artifact on Maven Central has none of those. Byte-identical to the jar
    // running on the test server, so what compiles here is what runs there.
    compileOnly(files("libs/UltimateAdvancementAPI-Plugin-2.8.0.jar"))
}

tasks.jar {
    archiveFileName.set("betterend-bukkit-${project.version}.jar")
    from(project(":common").the<SourceSetContainer>()["main"].output)
    from(project(":nms").the<SourceSetContainer>()["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Hoisted out of the task action: Task.project at execution time breaks the configuration
// cache, and without the declared input a version bump leaves plugin.yml UP-TO-DATE.
// Keep the filter on the two descriptors only -- expand() is a Groovy template engine and
// the CraftEngine configs are full of ${stone_type} placeholders it would choke on.
val pluginVersion = project.version.toString()

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand("version" to pluginVersion)
    }
}
