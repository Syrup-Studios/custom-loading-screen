package com.customloadingscreen.startup;

import static org.lwjgl.opengl.GL32C.*;

import com.customloadingscreen.render.TextureLoader;
import com.customloadingscreen.scene.Scene;
import java.util.HashMap;
import java.util.Map;
import net.neoforged.fml.earlydisplay.ElementShader;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import net.neoforged.fml.earlydisplay.SimpleFont;

/** Draws a scene into the early display's existing OpenGL context. */
public final class StartupRenderer implements AutoCloseable {
    private static final int TEXTURE_UNIT = 8;
    private static final int FONT_UNIT = 6;
    private static final Scene.Stage[] STAGES = Scene.Stage.values();
    private final Scene scene;
    private final SimpleFont font;
    private final TextBuffer textBuffer = new TextBuffer();
    private final TextureLoader textures = new TextureLoader();
    private Scene.Stage lastStage;
    private long lastFrameNanos = System.nanoTime();
    private long completeStartedNanos = -1;
    private long nextDebugNanos;
    private double fps;
    private String debugText = "";
    private final Map<String, Integer> tintCache = new HashMap<>();

    public StartupRenderer(Scene scene, SimpleFont font) {
        this.scene = scene;
        this.font = font;
        if (scene != null) for (Scene.Element element : scene.elements()) {
            if (switch (element.type.toLowerCase(java.util.Locale.ROOT)) {
                case "texture", "image", "logo", "sprite" -> !element.texture.isBlank();
                default -> false;
            }) textures.request(element.texture);
        }
    }

    public void render(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, int frame, int globalAlpha, StartupStageAdapter adapter) {
        if (scene == null) return;
        long now = System.nanoTime();
        double delta = (now - lastFrameNanos) / 1_000_000_000.0;
        if (delta > 0) fps = fps == 0 ? 1 / delta : fps * .9 + .1 / delta;
        lastFrameNanos = now;
        if (adapter != null) {
            adapter.poll();
            if (lastStage == null) {
                scene.onStageChanged(Scene.Stage.BOOT);
                lastStage = Scene.Stage.BOOT;
            }
            Scene.Stage next = adapter.stage();
            if (next.ordinal() > lastStage.ordinal()) {
                scene.update(adapter.elapsedSeconds(), adapter.overallProgress(), adapter.stageProgress());
                for (int i = lastStage.ordinal() + 1; i <= next.ordinal(); i++) scene.onStageChanged(STAGES[i]);
                lastStage = next;
                if (next == Scene.Stage.COMPLETE) completeStartedNanos = System.nanoTime();
            }
            scene.update(adapter.elapsedSeconds(), adapter.overallProgress(), adapter.stageProgress());
        }
        int width = Math.max(1, ctx.scaledWidth());
        int height = Math.max(1, ctx.scaledHeight());
        ElementShader shader = ctx.elementShader();
        shader.activate();
        shader.updateScreenSizeUniform(width, height);
        shader.updateTextureUniform(0);
        shader.updateRenderTypeUniform(ElementShader.RenderType.BAR);
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        double fit = Math.min(width / (double) scene.canvasWidth, height / (double) scene.canvasHeight);
        double offsetX = (width - scene.canvasWidth * fit) / 2;
        double offsetY = (height - scene.canvasHeight * fit) / 2;
        double elapsed = adapterTime(adapter);
        for (Scene.Element e : scene.elements()) {
            double x = e.currentX;
            double y = e.currentY;
            double w = e.currentWidth;
            double h = e.currentHeight;
            if ("center".equals(e.anchor)) { x -= w / 2; y -= h / 2; }
            if (adapter != null && adapter.overallProgress() >= 0 && e.type.equals("progress_bar")) w = e.currentWidth * adapter.overallProgress();
            int color = tint(e.tint, (int) Math.round(clamp(e.currentOpacity) * clamp(globalAlpha / 255.0) * 255));
            if (completeStartedNanos >= 0) {
                double fade = scene.exitDuration == 0 ? 0 : 1 - (System.nanoTime() - completeStartedNanos) / 1_000_000_000.0 / scene.exitDuration;
                color = withAlpha(color, (int) Math.round((color >>> 24) * clamp(fade)));
            }
            if ((color >>> 24) == 0 || w <= 0 || h <= 0) continue;
            switch (e.type.toLowerCase(java.util.Locale.ROOT)) {
                case "particles" -> {
                    shader.updateTextureUniform(0);
                    shader.updateRenderTypeUniform(ElementShader.RenderType.BAR);
                    drawParticles(bb, fit, offsetX, offsetY, x, y, w, h, e, color, elapsed);
                }
                case "text" -> drawText(ctx, fit, offsetX, offsetY, x, y, w, h, e, color);
                case "texture", "image", "logo", "sprite" -> {
                    if (e.texture.isBlank() && !e.text.isEmpty()) drawText(ctx, fit, offsetX, offsetY, x, y, w, h, e, color);
                    else {
                        int texture = textures.load(e.texture);
                        if (texture == 0 && !e.text.isEmpty()) drawText(ctx, fit, offsetX, offsetY, x, y, w, h, e, color);
                        else drawTexture(bb, shader, fit, offsetX, offsetY, x, y, w, h, e, color, texture);
                    }
                }
                default -> {
                    shader.updateTextureUniform(0);
                    shader.updateRenderTypeUniform(ElementShader.RenderType.BAR);
                    drawQuad(bb, fit, offsetX, offsetY, x, y, w, h, e.currentScaleX, e.currentScaleY, e.currentRotation, color);
                }
            }
        }
        if (scene.debug) drawDebug(ctx, height, fit, offsetX, offsetY, adapter, globalAlpha);
        glActiveTexture(GL_TEXTURE0);
        shader.clear();
    }

    private void drawTexture(SimpleBufferBuilder bb, ElementShader shader, double fit, double ox, double oy,
                             double x, double y, double w, double h, Scene.Element e, int color, int texture) {
        if (texture == 0) {
            shader.updateTextureUniform(0);
            shader.updateRenderTypeUniform(ElementShader.RenderType.BAR);
            drawQuad(bb, fit, ox, oy, x, y, w, h, e.currentScaleX, e.currentScaleY, e.currentRotation, color);
            return;
        }
        int iw = textures.width(e.texture), ih = textures.height(e.texture);
        double u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        if (e.frameWidth > 0 && e.frameHeight > 0 && iw >= e.frameWidth && ih >= e.frameHeight) {
            int columns = Math.max(1, iw / e.frameWidth);
            int rows = Math.max(1, ih / e.frameHeight);
            int frames = Math.min(e.frameCount, columns * rows);
            int index = e.spriteLoop ? Math.floorMod(e.currentFrame, frames) : Math.max(0, Math.min(e.currentFrame, frames - 1));
            int col = index % columns, row = index / columns;
            u0 = col * e.frameWidth / (double) iw; u1 = (col + 1) * e.frameWidth / (double) iw;
            v0 = row * e.frameHeight / (double) ih; v1 = (row + 1) * e.frameHeight / (double) ih;
        }
        shader.updateTextureUniform(TEXTURE_UNIT);
        shader.updateRenderTypeUniform(ElementShader.RenderType.TEXTURE);
        glActiveTexture(GL_TEXTURE0 + TEXTURE_UNIT);
        glBindTexture(GL_TEXTURE_2D, texture);
        bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        quad(bb, fit, ox, oy, x, y, w, h, e.currentScaleX, e.currentScaleY, e.currentRotation, color, u0, v0, u1, v1);
        bb.draw();
        glActiveTexture(GL_TEXTURE0);
    }

    private void drawText(RenderElement.DisplayContext ctx, double fit, double ox, double oy,
                          double x, double y, double w, double h, Scene.Element e, int color) {
        if (font == null || e.text.isEmpty()) return;
        ElementShader shader = ctx.elementShader();
        shader.updateTextureUniform(FONT_UNIT);
        shader.updateRenderTypeUniform(ElementShader.RenderType.FONT);
        int textWidth = font.stringWidth(e.text);
        double textScale = h > 0 ? h / 24 : 1;
        if (w > 0 && textWidth > 0) textScale = Math.min(textScale, w / textWidth);
        boolean centered = "center".equals(e.anchor);
        textBuffer.setTransform(centered ? x + w / 2 : x, centered ? y + h / 2 : y,
                centered ? -textWidth / 2.0 : 0, centered ? -12 : 0,
                e.currentScaleX * textScale, e.currentScaleY * textScale, e.currentRotation, fit, ox, oy);
        textBuffer.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        font.generateVerticesForTexts(0, 0, textBuffer, new SimpleFont.DisplayText(e.text, color));
        textBuffer.draw();
        textBuffer.resetTransform();
    }

    private static void drawParticles(SimpleBufferBuilder bb, double fit, double ox, double oy,
                                      double x, double y, double w, double h, Scene.Element e, int color, double time) {
        if (e.particleCount <= 0 || e.particleSize <= 0) return;
        double size = e.particleSize;
        double phase = time * e.particleSpeed;
        double cx = x + w / 2, cy = y + h / 2;
        double angle = Math.toRadians(e.currentRotation), cos = Math.cos(angle), sin = Math.sin(angle);
        bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        for (int i = 0; i < e.particleCount; i++) {
            double seed = i * 0.6180339887498949;
            double dx = positiveMod(fract(seed) * w + phase * .5, w) - w / 2 + Math.sin(phase + i * 2.4) * e.particleSpread;
            double dy = positiveMod(fract(seed * 1.73) * h - phase, h) - h / 2 + Math.cos(phase * .8 + i) * e.particleSpread;
            double px = cx + dx * e.currentScaleX * cos - dy * e.currentScaleY * sin;
            double py = cy + dx * e.currentScaleX * sin + dy * e.currentScaleY * cos;
            quad(bb, fit, ox, oy, px, py, size, size, e.currentScaleX, e.currentScaleY, e.currentRotation, color, 0, 0, 0, 0);
        }
        bb.draw();
    }

    private static final class TextBuffer extends SimpleBufferBuilder {
        double x, y, tx, ty, sx, sy, cos, sin, fit, ox, oy;
        boolean transformed;
        TextBuffer() { super(1024); }
        void setTransform(double x, double y, double tx, double ty, double sx, double sy, double degrees, double fit, double ox, double oy) {
            this.x = x; this.y = y; this.tx = tx; this.ty = ty; this.sx = sx; this.sy = sy;
            double angle = Math.toRadians(degrees);
            this.cos = Math.cos(angle); this.sin = Math.sin(angle);
            this.fit = fit; this.ox = ox; this.oy = oy; transformed = true;
        }
        void resetTransform() { transformed = false; }
        @Override public SimpleBufferBuilder pos(float x, float y) {
            if (transformed) {
                double dx = (x + tx) * sx, dy = (y + ty) * sy;
                double px = this.x + dx * cos - dy * sin;
                double py = this.y + dx * sin + dy * cos;
                x = (float) (ox + px * fit); y = (float) (oy + py * fit);
            }
            return super.pos(x, y);
        }
    }

    private static void drawQuad(SimpleBufferBuilder bb, double fit, double ox, double oy,
                                 double x, double y, double w, double h, double sx, double sy, double rotation, int color) {
        bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        quad(bb, fit, ox, oy, x, y, w, h, sx, sy, rotation, color, 0, 0, 0, 0);
        bb.draw();
    }

    private static void quad(SimpleBufferBuilder bb, double fit, double ox, double oy,
                             double x, double y, double w, double h, double sx, double sy, double degrees,
                             int color, double u0, double v0, double u1, double v1) {
        double cx = x + w / 2, cy = y + h / 2, angle = Math.toRadians(degrees), c = Math.cos(angle), s = Math.sin(angle);
        vertex(bb, cx, cy, -w / 2, -h / 2, sx, sy, c, s, fit, ox, oy, u0, v0, color);
        vertex(bb, cx, cy, w / 2, -h / 2, sx, sy, c, s, fit, ox, oy, u1, v0, color);
        vertex(bb, cx, cy, -w / 2, h / 2, sx, sy, c, s, fit, ox, oy, u0, v1, color);
        vertex(bb, cx, cy, w / 2, h / 2, sx, sy, c, s, fit, ox, oy, u1, v1, color);
    }

    private static void vertex(SimpleBufferBuilder bb, double cx, double cy, double dx, double dy, double sx, double sy,
                               double c, double s, double fit, double ox, double oy, double u, double v, int color) {
        double px = dx * sx, py = dy * sy;
        double x = ox + (cx + px * c - py * s) * fit;
        double y = oy + (cy + px * s + py * c) * fit;
        bb.pos((float) x, (float) y).tex((float) u, (float) v).colour(color).endVertex();
    }

    private void drawDebug(RenderElement.DisplayContext ctx, int sh, double fit, double ox, double oy,
                           StartupStageAdapter adapter, int alpha) {
        if (font == null) return;
        long now = System.nanoTime();
        if (now >= nextDebugNanos) {
            String stage = adapter == null ? scene.stage().name() : adapter.stage().name();
            String overall = adapter == null || adapter.overallProgress() < 0 ? "unknown" : (int) (adapter.overallProgress() * 100) + "%";
            String local = adapter == null || adapter.stageProgress() < 0 ? "unknown" : (int) (adapter.stageProgress() * 100) + "%";
            String mods = adapter == null || adapter.totalModCount() < 0 ? "" : " Mods " + adapter.loadedModCount() + "/" + adapter.totalModCount();
            String active = String.join(",", scene.activeAnimationNames());
            debugText = stage + " " + overall + " Stage " + local + mods + "\n" + String.format(java.util.Locale.ROOT, "%.0f FPS  %.1f s", fps, adapter == null ? 0 : adapter.elapsedSeconds()) + "\nActive: " + active;
            nextDebugNanos = now + 250_000_000L;
        }
        ElementShader shader = ctx.elementShader();
        shader.updateTextureUniform(FONT_UNIT);
        shader.updateRenderTypeUniform(ElementShader.RenderType.FONT);
        textBuffer.setTransform(16, sh / fit - 80, 0, 0, 1, 1, 0, fit, ox, oy);
        textBuffer.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        int color = 0xE6FFFFFF & 0x00FFFFFF | ((Math.max(0, Math.min(255, alpha * 9 / 10))) << 24);
        font.generateVerticesForTexts(0, 0, textBuffer, new SimpleFont.DisplayText(debugText, color));
        textBuffer.draw();
        textBuffer.resetTransform();
    }

    private int tint(String value, int alpha) {
        Integer packed = tintCache.get(value);
        if (packed == null) {
            packed = parseTint(value);
            tintCache.put(value, packed);
        }
        return withAlpha(packed, ((packed >>> 24) * alpha / 255));
    }

    private static int parseTint(String value) {
        try {
            String hex = value.startsWith("#") ? value.substring(1) : value;
            long rgba = Long.parseLong(hex, 16);
            int r = (int) (rgba >>> 24) & 255, g = (int) (rgba >>> 16) & 255, b = (int) (rgba >>> 8) & 255;
            int a = (int) rgba & 255;
            return (a << 24) | (b << 16) | (g << 8) | r;
        } catch (RuntimeException ex) { return 0xFFFFFFFF; }
    }

    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }

    private static double clamp(double n) { return Double.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0; }
    private static double fract(double n) { return n - Math.floor(n); }
    private static double positiveMod(double n, double mod) { return ((n % mod) + mod) % mod; }
    private static double adapterTime(StartupStageAdapter adapter) { return adapter == null ? 0 : adapter.elapsedSeconds(); }

    @Override public void close() {
        textBuffer.close();
        textures.close();
    }
}
