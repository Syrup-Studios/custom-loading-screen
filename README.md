# Custom Loading Screen

This repository contains a client-side mod scaffold for NeoForge on Minecraft 1.21.1. The entrypoint is empty; custom loading screen behavior is not implemented.

Use Java 21. Gradle can select this toolchain with the Foojay resolver.

Build the target with `./gradlew buildAndCollect`. Run it with `./gradlew :1.21.1-neoforge:runClient`. The mod and sources jars go to `build/libs/1.0.0/`.

Stonecutter keeps the single `1.21.1-neoforge` node active and as the VCS version. The NeoForge metadata registers an empty mixin config for future client mixin classes.
