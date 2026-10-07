plugins {
    id("net.neoforged.moddev")
    id("me.modmuss50.mod-publish-plugin")
}

val minecraftVersion = "1.21.1"
val targetJavaVersion = 21
val sourceSets = extensions.getByType<SourceSetContainer>()

sourceSets.named("main") {
    java.include(
        "com/customloadingscreen/CustomLoadingScreen.java",
        "com/customloadingscreen/overlay/SmoothLoadingOverlay.java",
        "com/customloadingscreen/bridge/**",
        "com/customloadingscreen/mixin/**"
    )
    resources.include("META-INF/neoforge.mods.toml", "customloadingscreen.mixins.json")
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
        "homepage" to project.property("mod.homepage"),
        "issues" to project.property("mod.issues"),
        "minecraft" to "[$minecraftVersion]",
        "neoforge" to project.property("deps.neoforge_version")
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("${project.version}+$minecraftVersion-neoforge")
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE.md"))
}

val modJar = tasks.register<Jar>("modJar") {
    archiveClassifier.set("internal-mod")
    from(sourceSets.named("main").get().output)
}

tasks.named<Jar>("jar") {
    // FML loads this provider jar in the service layer and discovers the mod from the nested jar.
    exclude(
        "com/customloadingscreen/CustomLoadingScreen.class",
        "com/customloadingscreen/overlay/**",
        "com/customloadingscreen/bridge/**",
        "com/customloadingscreen/mixin/**",
        "customloadingscreen.mixins.json",
        "META-INF/neoforge.mods.toml"
    )
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

val archiveVersion = "${project.version}+$minecraftVersion-neoforge"
val curseForgeToken = providers.gradleProperty("publish.curseforge_token")
    .orElse(providers.environmentVariable("CURSEFORGE_TOKEN"))
val modrinthToken = providers.gradleProperty("publish.modrinth_token")
    .orElse(providers.environmentVariable("MODRINTH_TOKEN"))
val compatibleVersions = listOf(minecraftVersion)

publishMods {
    file = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dryRun = false
    version = archiveVersion
    displayName = "${property("mod.name")} ${project.version} - NeoForge $minecraftVersion"
    changelog = providers.fileContents(rootProject.layout.projectDirectory.file("CHANGELOG.md")).asText
    type = when (property("publish.release_type").toString().lowercase()) {
        "stable" -> STABLE
        "beta" -> BETA
        "alpha" -> ALPHA
        else -> error("publish.release_type must be stable, beta, or alpha")
    }
    modLoaders.add("neoforge")

    curseforge {
        projectId = property("publish.curseforge").toString()
        accessToken = curseForgeToken
        compatibleVersions.forEach { minecraftVersions.add(it) }
        client = true
        server = false
    }
    modrinth {
        projectId = property("publish.modrinth").toString()
        accessToken = modrinthToken
        compatibleVersions.forEach { minecraftVersions.add(it) }
        environment = CLIENT_ONLY
    }
}

tasks.matching { it.name == "publishCurseforge" || it.name == "publishModrinth" }.configureEach {
    doFirst {
        if (name == "publishCurseforge") {
            check(project.property("publish.curseforge").toString().isNotBlank()) {
                "Set publish.curseforge before uploading."
            }
            check(curseForgeToken.isPresent && !curseForgeToken.get().isBlank()) {
                "Set publish.curseforge_token or CURSEFORGE_TOKEN before uploading."
            }
        } else {
            check(project.property("publish.modrinth").toString().isNotBlank()) {
                "Set publish.modrinth before uploading."
            }
            check(modrinthToken.isPresent && !modrinthToken.get().isBlank()) {
                "Set publish.modrinth_token or MODRINTH_TOKEN before uploading."
            }
        }
    }
}
