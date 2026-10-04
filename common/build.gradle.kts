dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paper_version")}")
    compileOnly("net.momirealms:craft-engine-core:${property("craftengine_version")}")
    compileOnly("net.momirealms:craft-engine-bukkit:${property("craftengine_version")}")

    // The tests name nothing outside java.* -- but VanillaEndCheck implements org.bukkit.event.Listener,
    // and javac has to resolve a class's supertypes to call even a pure static method on it, so
    // paper-api is mirrored here. CraftEngine deliberately is NOT: nothing tested touches it.
    testCompileOnly("io.papermc.paper:paper-api:${property("paper_version")}")
    testRuntimeOnly("io.papermc.paper:paper-api:${property("paper_version")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
