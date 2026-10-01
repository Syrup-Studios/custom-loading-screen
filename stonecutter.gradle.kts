plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.147" apply false
}

stonecutter active "1.21.1-neoforge"

stonecutter.parameters {
    val (version, loader) = current.project.split('-', limit = 2)
    properties { tags(version, loader) }
}
