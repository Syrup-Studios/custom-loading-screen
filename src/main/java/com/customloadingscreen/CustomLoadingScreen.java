package com.customloadingscreen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mod(value = "customloadingscreen", dist = Dist.CLIENT)
public final class CustomLoadingScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomLoadingScreen.class);
    private static Object stageProvider;
    private static Method stageMethod;
    private static boolean providerResolved;
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
            if (!providerResolved) resolveStageProvider();
            if (stageMethod != null) stageMethod.invoke(stageProvider, stage);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!stageFailureLogged) {
                stageFailureLogged = true;
                LOGGER.warn("Could not update the early loading stage", error);
            }
        }
    }

    private static void resolveStageProvider() throws ReflectiveOperationException {
        Field providerField = ImmediateWindowHandler.class.getDeclaredField("provider");
        providerField.setAccessible(true);
        Object earlyProvider = providerField.get(null);
        providerResolved = true;
        if (earlyProvider == null || !earlyProvider.getClass().getName().equals("com.customloadingscreen.startup.StartupWindowProvider")) return;
        stageProvider = earlyProvider;
        stageMethod = earlyProvider.getClass().getMethod("lifecycleStage", String.class);
    }

}
