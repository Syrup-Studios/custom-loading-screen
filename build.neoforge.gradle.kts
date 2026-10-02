plugins {
    id("net.neoforged.moddev")
}

val minecraftVersion = "1.21.1"
val targetJavaVersion = 21
val sourceSets = extensions.getByType<SourceSetContainer>()

sourceSets.named("main") {
    java.include("com/customloadingscreen/CustomLoadingScreen.java")
    resources.include("META-INF/neoforge.mods.toml")
}

val earlyProvider = sourceSets.create("earlyProvider") {
    java.setSrcDirs(listOf(layout.buildDirectory.dir("generated/stonecutter/main/java")))
    java.include("com/customloadingscreen/config/**", "com/customloadingscreen/render/**", "com/customloadingscreen/scene/**", "com/customloadingscreen/startup/**")
    resources.setSrcDirs(listOf(layout.buildDirectory.dir("generated/stonecutter/main/resources")))
    resources.include(
        "META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider",
        "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
        "assets/**"
    )
}
earlyProvider.compileClasspath += sourceSets.named("main").get().compileClasspath
val earlyProviderRuntime = dependencies.create(earlyProvider.output)

tasks.named<JavaCompile>("compileEarlyProviderJava") { dependsOn("stonecutterGenerate") }
tasks.named("processEarlyProviderResources") { dependsOn("stonecutterGenerate") }

version = property("mod.version") as String
group = property("mod.group") as String
base.archivesName = property("mod.id") as String

dependencies {
    compileOnly("net.neoforged.fancymodloader:earlydisplay:4.0.41")
}

neoForge {
    version = property("deps.neoforge_version") as String
    mods.create(property("mod.id") as String) { sourceSet(sourceSets.main.get()) }
    runs {
        create("client") {
            client()
            gameDirectory = rootProject.file("run")
            additionalRuntimeClasspathConfiguration.dependencies.add(earlyProviderRuntime)
        }
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

val modJar = tasks.register<Jar>("modJar") {
    archiveClassifier.set("internal-mod")
    from(sourceSets.named("main").get().output)
}

tasks.named<Jar>("jar") {
    // FML loads this provider jar in the service layer and discovers the mod from the nested jar.
    exclude("com/customloadingscreen/CustomLoadingScreen.class", "META-INF/neoforge.mods.toml")
    from(earlyProvider.output)
    from(modJar.flatMap { it.archiveFile }) {
        into("META-INF/jarjar")
        rename { "customloadingscreen-mod.jar" }
    }
}

tasks.named<Jar>("sourcesJar") {
    from(earlyProvider.allJava)
    dependsOn("stonecutterGenerate")
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(tasks.named<Jar>("jar"), tasks.named<Jar>("sourcesJar"))
    into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    dependsOn("build")
}
