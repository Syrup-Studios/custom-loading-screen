package com.customloadingscreen;

import com.customloadingscreen.bridge.WindowHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = "customloadingscreen", dist = Dist.CLIENT)
public final class CustomLoadingScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomLoadingScreen.class);
    private static boolean stageFailureLogged;

    public CustomLoadingScreen(IEventBus modBus) {
        publishStage("MOD_CONSTRUCTION");
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::loadComplete);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        publishStage("COMMON_SETUP");
    }

    private void clientSetup(FMLClientSetupEvent event) {
        publishStage("CLIENT_SETUP");
    }

    private void loadComplete(FMLLoadCompleteEvent event) {
        publishStage("FINALIZING");
    }

    private static synchronized void publishStage(String stage) {
        try {
            WindowHooks.publishStage(stage);
        } catch (RuntimeException error) {
            if (!stageFailureLogged) {
                stageFailureLogged = true;
                LOGGER.warn("Could not update the early loading stage", error);
            }
        }
    }
}
