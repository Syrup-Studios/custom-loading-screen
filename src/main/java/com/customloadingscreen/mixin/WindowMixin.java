package com.customloadingscreen.mixin;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.customloadingscreen.bridge.WindowHooks;

@Mixin(Window.class)
abstract class WindowMixin {
    @Shadow private boolean fullscreen;
    @Shadow private long window;

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void customloadingscreen$deferFullscreen(CallbackInfo callback) {
        if (fullscreen && WindowHooks.deferFullscreen(window)) callback.cancel();
    }

    @Inject(method = "updateDisplay", at = @At("RETURN"))
    private void customloadingscreen$revealAfterSwap(CallbackInfo callback) {
        WindowHooks.revealAfterSwap(window);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void customloadingscreen$closeEarlyDisplay(CallbackInfo callback) {
        WindowHooks.close(window);
    }
}
