plugins {
    `base`
}

allprojects {
    group = "org.betterx"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.momirealms.net/releases/")
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Gradle's default test worker is 512m and the suite reliably dies inside it with no
        // report at all (the worker exits, and Gradle surfaces it as an EOFException reading its
        // own result store). IslandFieldTest and BiomeProviderTest each hold several IslandFields,
        // whose plane memo is capped at 1 << 16 entries of double[50] -- ~27 MB per live field
        // before garbage. Raising the worker, not shrinking the caches: the caches are the thing
        // under test.
        maxHeapSize = "2g"
    }
}
