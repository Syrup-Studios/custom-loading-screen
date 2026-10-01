plugins {
    id("net.neoforged.moddev")
}

val minecraftVersion = "1.21.1"
val targetJavaVersion = 21

version = property("mod.version") as String
group = property("mod.group") as String
base.archivesName = property("mod.id") as String

neoForge {
    version = property("deps.neoforge_version") as String
    mods.create(property("mod.id") as String) { sourceSet(sourceSets.main.get()) }
    runs {
        create("client") { client(); gameDirectory = rootProject.file("run") }
    }
}

java {
    withSourcesJar()
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "modId" to project.property("mod.id"),
        "modName" to project.property("mod.name"),
        "modDescription" to project.property("mod.description"),
        "authors" to project.property("mod.authors"),
        "license" to project.property("mod.license"),
        "minecraft" to "[$minecraftVersion]",
        "neoforge" to project.property("deps.neoforge_version")
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("${project.version}+$minecraftVersion-neoforge")
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(tasks.named<Jar>("jar"), tasks.named<Jar>("sourcesJar"))
    into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    dependsOn("build")
}
