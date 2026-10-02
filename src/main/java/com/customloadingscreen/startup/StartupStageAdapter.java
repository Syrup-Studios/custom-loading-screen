package com.customloadingscreen.startup;

import com.customloadingscreen.scene.Scene;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class StartupStageAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(StartupStageAdapter.class);
    private static final Scene.Stage[] STAGES = Scene.Stage.values();
    private final long startedAt = System.nanoTime();
    private volatile Scene.Stage stage = Scene.Stage.BOOT;
    private volatile float overallProgress = -1;
    private volatile float stageProgress = -1;
    private volatile int loadedModCount = -1;
    private volatile int totalModCount = -1;
    private volatile boolean complete;

    public synchronized void poll() {
        if (complete) return;
        List<ProgressMeter> meters = StartupNotificationManager.getCurrentProgress();
        ProgressMeter active = null;
        Scene.Stage activeStage = stage;
        for (ProgressMeter meter : meters) {
            Scene.Stage detected = stageFor(meter.name(), meter.label().getText(), stage);
            boolean finalResourceMeter = isMinecraftProgress(meter) && stage == Scene.Stage.FINALIZING;
            if (detected != null && detected.ordinal() >= activeStage.ordinal()) {
                activeStage = detected;
                active = meter;
            } else if (finalResourceMeter) {
                active = meter;
            }
        }
        if (activeStage.ordinal() > stage.ordinal()) {
            markStage(activeStage);
        }
        if (active != null && activeStage == stage && active.steps() > 0) {
            stageProgress = clamp(active.progress());
            if (active.name().toLowerCase(java.util.Locale.ROOT).contains("mod construction")) {
                loadedModCount = Math.max(0, active.current());
                totalModCount = Math.max(0, active.steps());
            }
            float value = (stage.ordinal() + stageProgress) / (STAGES.length - 1);
            if (overallProgress < 0 || value > overallProgress) overallProgress = Math.min(0.99f, value);
        }
    }

    public Scene.Stage stage() { return stage; }
    public float overallProgress() { return overallProgress; }
    public float stageProgress() { return stageProgress; }
    public double elapsedSeconds() { return (System.nanoTime() - startedAt) / 1_000_000_000.0; }
    public int loadedModCount() { return loadedModCount; }
    public int totalModCount() { return totalModCount; }

    public synchronized void markStage(Scene.Stage next) {
        if (next == null || next.ordinal() < stage.ordinal()) return;
        if (next == stage) return;
        LOGGER.info("Startup stage: {}", next);
        stage = next;
        stageProgress = -1;
    }

    public synchronized void complete() {
        complete = true;
        markStage(Scene.Stage.COMPLETE);
        stageProgress = 1;
        overallProgress = 1;
    }

    private static Scene.Stage stageFor(String name, String label, Scene.Stage current) {
        String value = (name + " " + label).toLowerCase(java.util.Locale.ROOT);
        if (value.contains("discover") || value.contains("scanning mod")) return Scene.Stage.MOD_DISCOVERY;
        if (value.contains("construct")) return Scene.Stage.MOD_CONSTRUCTION;
        if (value.contains("common setup") || value.contains("common_setup")) return Scene.Stage.COMMON_SETUP;
        if (value.contains("client setup") || value.contains("sided setup")) return Scene.Stage.CLIENT_SETUP;
        if (isMinecraftProgress(name, label) && current.ordinal() >= Scene.Stage.CLIENT_SETUP.ordinal()) return Scene.Stage.RESOURCE_LOADING;
        if (value.contains("final")) return Scene.Stage.FINALIZING;
        return null;
    }

    private static boolean isMinecraftProgress(ProgressMeter meter) {
        return isMinecraftProgress(meter.name(), meter.label().getText());
    }

    private static boolean isMinecraftProgress(String name, String label) {
        return name.equalsIgnoreCase("Minecraft Progress");
    }

    private static float clamp(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : -1;
    }
}
