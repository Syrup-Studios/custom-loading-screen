package com.customloadingscreen.startup;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

public final class EmbeddedModLocator implements IModFileCandidateLocator {
    private static final String EMBEDDED_MOD = "/META-INF/jarjar/customloadingscreen-mod.jar";

    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        var resource = getClass().getResource(EMBEDDED_MOD);
        if (resource == null) {
            var location = getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location != null && location.toString().endsWith(".jar")) {
                throw new IllegalStateException("Missing embedded mod: " + EMBEDDED_MOD);
            }
            return;
        }

        try {
            Path embeddedMod = Path.of(resource.toURI());
            URI uri = new URI("jij:" + embeddedMod.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
            var fileSystem = FileSystems.newFileSystem(uri, Map.of("packagePath", embeddedMod));
            pipeline.addJarContent(
                    JarContents.of(fileSystem.getPath("/")),
                    ModFileDiscoveryAttributes.DEFAULT,
                    IncompatibleFileReporting.ERROR);
        } catch (Exception error) {
            throw new IllegalStateException("Could not load embedded mod: " + EMBEDDED_MOD, error);
        }
    }
}
