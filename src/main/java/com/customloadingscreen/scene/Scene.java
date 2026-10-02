package com.customloadingscreen.scene;

import com.customloadingscreen.config.SceneLimits;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renderer-independent scene state. Call update with seconds from a monotonic clock. */
public final class Scene {
    private static final int PROPERTY_COUNT = 9;
    public enum Stage { BOOT, MOD_DISCOVERY, MOD_CONSTRUCTION, COMMON_SETUP, CLIENT_SETUP, RESOURCE_LOADING, FINALIZING, COMPLETE }
    public enum Easing { LINEAR, IN_QUAD, OUT_QUAD, IN_OUT_QUAD, OUT_CUBIC, IN_OUT_CUBIC, OUT_BACK }
    public enum Playback { ONCE, LOOP, PINGPONG }
    public enum Trigger { TIME, OVERALL_PROGRESS, STAGE_PROGRESS, STAGE }

    public static final class Element {
        public final String id, type, texture, text, anchor;
        public final int layer, frameWidth, frameHeight, frameCount, particleCount;
        public final double x, y, width, height, scaleX, scaleY, rotation, opacity, fps, parallaxX, parallaxY, parallaxPeriod, particleSpeed, particleSize, particleSpread;
        public final boolean spriteLoop;
        public final List<Animation> animations;
        private final Animation[] winners = new Animation[PROPERTY_COUNT];
        public double currentX, currentY, currentWidth, currentHeight, currentScaleX, currentScaleY, currentRotation, currentOpacity;
        public int currentFrame;
        public String tint;

        Element(JsonObject o, int index) {
            id = string(o,"id","element_"+index); type=string(o,"type","rect"); texture=string(o,"texture",""); text=string(o,"text","");anchor=string(o,"anchor","top_left");
            layer=integer(o,"layer",0); x=num(o,"x",0); y=num(o,"y",0); width=num(o,"width",0); height=num(o,"height",0);
            scaleX=num(o,"scaleX",1); scaleY=num(o,"scaleY",1); rotation=num(o,"rotation",0); opacity=num(o,"opacity",1);
            parallaxX=num(o,"parallaxX",0); parallaxY=num(o,"parallaxY",0); parallaxPeriod=num(o,"parallaxPeriod",4);
            frameWidth=integer(o,"frameWidth",0); frameHeight=integer(o,"frameHeight",0); frameCount=integer(o,"frameCount",1); fps=num(o,"fps",0); spriteLoop=bool(o,"spriteLoop",true);
            particleCount=integer(o,"count",32);particleSpeed=num(o,"speed",20);particleSize=num(o,"size",3);particleSpread=num(o,"spread",1);
            tint=string(o,"tint","#FFFFFFFF");
            currentX=x; currentY=y; currentWidth=width; currentHeight=height; currentScaleX=scaleX; currentScaleY=scaleY; currentRotation=rotation; currentOpacity=opacity;
            if (o.has("animations") && !o.get("animations").isJsonArray()) throw new IllegalArgumentException("animations must be an array on " + id);
            JsonArray a=o.has("animations")?o.getAsJsonArray("animations"):new JsonArray();
            if(a.size()>SceneLimits.MAX_ANIMATIONS_PER_ELEMENT) throw new IllegalArgumentException("Too many animations on "+id);
            animations=new ArrayList<>();
            for(JsonElement e:a) {
                if (!e.isJsonObject()) throw new IllegalArgumentException("Animation must be an object on " + id);
                animations.add(new Animation(e.getAsJsonObject()));
            }
            orderAndValidateChains(animations);
        }
    }

    public static final class Animation {
        public final String name, property, chain;
        public final double from, to, duration, delay;
        public final Easing easing;
        public final Playback playback;
        public final Trigger trigger;
        public final double threshold;
        public final Stage stage;
        private double activatedAt=Double.NaN;
        private double completedAt=Double.NaN;
        private double lastValue;
        private boolean complete;
        Animation(JsonObject o) {
            name=string(o,"name",""); property=string(o,"property","y"); from=num(o,"from",0); to=num(o,"to",0); duration=num(o,"duration",1); delay=num(o,"delay",0);
            easing=enumValue(Easing.class,string(o,"easing","linear")); playback=enumValue(Playback.class,string(o,"playback","once"));
            trigger=enumValue(Trigger.class,string(o,"trigger","time")); threshold=num(o,"threshold",0);
            stage=o.has("stage")?enumValue(Stage.class,string(o,"stage","BOOT")):null; chain=string(o,"onComplete","");
            if (!Double.isFinite(duration) || !Double.isFinite(delay) || duration <= 0 || duration > SceneLimits.MAX_DURATION_SECONDS || delay < 0 || delay > SceneLimits.MAX_DURATION_SECONDS || !Double.isFinite(from) || Math.abs(from) > SceneLimits.MAX_DIMENSION || !Double.isFinite(to) || Math.abs(to) > SceneLimits.MAX_DIMENSION || !Double.isFinite(threshold) || threshold < 0 || threshold > 1 || propertySlot(property) < 0 || (trigger == Trigger.STAGE && stage == null)) {
                throw new IllegalArgumentException("Invalid animation");
            }
        }
        private void activate(double now) { if (Double.isNaN(activatedAt)) activatedAt = now + delay; }
        private Double sample(double now) {
            if ((trigger != Trigger.TIME && trigger != Trigger.STAGE) || Double.isNaN(activatedAt)) return null;
            if (complete) return lastValue;
            double t = (now - activatedAt) / duration;
            if (t < 0) return from;
            if (playback == Playback.ONCE && t >= 1) { complete = true; completedAt = activatedAt + duration; lastValue = to; return to; }
            if (playback == Playback.LOOP) t -= Math.floor(t);
            if (playback == Playback.PINGPONG) { double phase = t % 2; t = phase <= 1 ? phase : 2 - phase; }
            lastValue = from + (to - from) * ease(easing, Math.max(0, Math.min(1, t)));
            return lastValue;
        }
    }

    public final int canvasWidth, canvasHeight;
    public final boolean debug;
    public final double exitDuration;
    private final List<Element> elements;
    private double time;
    private boolean hasTime;
    public double overallProgress=-1, stageProgress=-1;
    private Stage stage=Stage.BOOT;

    private Scene(int w,int h,List<Element> e,boolean debug,double exitDuration){canvasWidth=w;canvasHeight=h;elements=e;elements.sort(Comparator.comparingInt(x->x.layer));this.debug=debug;this.exitDuration=exitDuration;}
    public int canvasWidth(){return canvasWidth;} public int canvasHeight(){return canvasHeight;}
    public List<Element> elements(){return elements;} public Stage stage(){return stage;}

    public void onStageChanged(Stage next) {
        if (next == null) return;
        stage = next;
        if (hasTime) activateStage(next, time);
    }

    public void update(double timeSeconds, double overallProgress, double stageProgress) {
        if (!Double.isFinite(timeSeconds)) return;
        time = Math.max(0, timeSeconds);
        this.overallProgress = progress(overallProgress);
        this.stageProgress = progress(stageProgress);
        if (!hasTime) {
            hasTime = true;
            activateStage(stage, time);
            for (Element element : elements) for (Animation animation : element.animations) {
                if (animation.trigger == Trigger.TIME && !hasParent(element.animations, animation)) animation.activate(time);
            }
        }

        for (Element element : elements) {
            resetState(element, time);
            Arrays.fill(element.winners, null);
            for (Animation animation : element.animations) {
                boolean chained = hasParent(element.animations, animation);
                if (chained && !parentComplete(element.animations, animation)) continue;
                double parentEnd = parentEnd(element.animations, animation);
                if (!Double.isNaN(parentEnd)) animation.activate(parentEnd);
                double driver = switch (animation.trigger) {
                    case OVERALL_PROGRESS -> this.overallProgress;
                    case STAGE_PROGRESS -> this.stageProgress;
                    case STAGE, TIME -> Double.NaN;
                };
                Double value;
                if (animation.trigger == Trigger.TIME || animation.trigger == Trigger.STAGE) {
                    value = animation.sample(time);
                } else {
                    if (animation.complete) {
                        applyIfNewest(element, animation, animation.lastValue);
                        continue;
                    }
                    if (Double.isNaN(driver) || driver < animation.threshold) {
                        if (!Double.isNaN(animation.activatedAt)) applyIfNewest(element, animation, animation.lastValue);
                        continue;
                    }
                    animation.activate(time);
                    value = animation.from + (animation.to - animation.from) * ease(animation.easing, clamp(driver));
                    animation.lastValue = value;
                    if (animation.playback == Playback.ONCE && driver >= 1) {
                        animation.complete = true;
                        animation.completedAt = time;
                    }
                }
                if (value != null) applyIfNewest(element, animation, value);
            }
            if (element.parallaxPeriod > 0) {
                double wave = Math.sin(time * 2 * Math.PI / element.parallaxPeriod);
                element.currentX += element.parallaxX * wave;
                element.currentY += element.parallaxY * wave;
            }
        }
    }

    public List<String> activeAnimationNames(){List<String> r=new ArrayList<>();for(Element e:elements)for(Animation a:e.animations)if(!Double.isNaN(a.activatedAt)&&!a.complete)r.add(e.id+":"+a.property);return List.copyOf(r);}

    private void activateStage(Stage value, double at) {
        for (Element element : elements) for (Animation animation : element.animations) {
            if (animation.trigger == Trigger.STAGE && animation.stage == value && !hasParent(element.animations, animation)) animation.activate(at);
        }
    }

    private static void resetState(Element e, double time) {
        e.currentX = e.x; e.currentY = e.y; e.currentWidth = e.width; e.currentHeight = e.height;
        e.currentScaleX = e.scaleX; e.currentScaleY = e.scaleY; e.currentRotation = e.rotation; e.currentOpacity = e.opacity;
        if (e.frameWidth > 0 && e.frameHeight > 0 && e.fps > 0) {
            int frame = (int) (time * e.fps);
            e.currentFrame = e.spriteLoop ? Math.floorMod(frame, e.frameCount) : Math.min(frame, e.frameCount - 1);
        } else e.currentFrame = 0;
    }

    private static void applyIfNewest(Element e, Animation a, double value) {
        if (a.property.equals("scale")) {
            applyIfNewest(e, a, value, 4);
            applyIfNewest(e, a, value, 5);
            return;
        }
        int slot = propertySlot(a.property);
        if (slot >= 0) applyIfNewest(e, a, value, slot);
    }

    private static void applyIfNewest(Element e, Animation a, double value, int slot) {
        Animation previous = e.winners[slot];
        if (previous == null || a.activatedAt >= previous.activatedAt) {
            e.winners[slot] = a;
            apply(e, slot, value);
        }
    }

    private static boolean hasParent(List<Animation> animations, Animation child) {
        if (child.name.isEmpty()) return false;
        for (Animation parent : animations) if (parent.chain.equals(child.name)) return true;
        return false;
    }

    private static boolean parentComplete(List<Animation> animations, Animation child) {
        for (Animation parent : animations) if (parent.chain.equals(child.name)) return parent.complete;
        return true;
    }

    private static double parentEnd(List<Animation> animations, Animation child) {
        for (Animation parent : animations) if (parent.chain.equals(child.name) && parent.complete) return parent.completedAt;
        return Double.NaN;
    }

    private static void orderAndValidateChains(List<Animation> animations) {
        Map<String, Animation> named = new HashMap<>();
        for (Animation animation : animations) {
            if (!animation.name.isEmpty() && named.putIfAbsent(animation.name, animation) != null) {
                throw new IllegalArgumentException("Duplicate animation name: " + animation.name);
            }
        }
        Map<String, Animation> parentByChild = new HashMap<>();
        for (Animation parent : animations) {
            if (parent.chain.isEmpty()) continue;
            if (!named.containsKey(parent.chain)) throw new IllegalArgumentException("Unknown onComplete animation: " + parent.chain);
            if (parentByChild.putIfAbsent(parent.chain, parent) != null) throw new IllegalArgumentException("Multiple onComplete parents: " + parent.chain);
        }
        List<Animation> ordered = new ArrayList<>(animations.size());
        while (ordered.size() < animations.size()) {
            Animation next = null;
            for (Animation candidate : animations) {
                if (ordered.contains(candidate)) continue;
                Animation parent = parentByChild.get(candidate.name);
                if (parent == null || ordered.contains(parent)) { next = candidate; break; }
            }
            if (next == null) throw new IllegalArgumentException("Animation onComplete chain contains a cycle");
            ordered.add(next);
        }
        animations.clear();
        animations.addAll(ordered);
    }

    public static Scene load(InputStream input) throws IOException {
        if(input==null) throw new IOException("Missing scene JSON");
        JsonObject root; try(InputStreamReader reader=new InputStreamReader(input,StandardCharsets.UTF_8)){JsonElement parsed=JsonParser.parseReader(reader);if(!parsed.isJsonObject())throw new IllegalArgumentException("Scene root must be an object");root=parsed.getAsJsonObject();}
        int w=integer(root,"canvasWidth",1920),h=integer(root,"canvasHeight",1080);
        if(w<1||h<1||w>SceneLimits.MAX_DIMENSION||h>SceneLimits.MAX_DIMENSION)throw new IllegalArgumentException("Invalid scene canvas size");
        if (root.has("elements") && !root.get("elements").isJsonArray()) throw new IllegalArgumentException("elements must be an array");
        JsonArray items=root.has("elements")?root.getAsJsonArray("elements"):new JsonArray();
        if(items.size()>SceneLimits.MAX_ELEMENTS)throw new IllegalArgumentException("Too many scene elements");
        List<Element> elements=new ArrayList<>();
        Map<String, Boolean> ids = new HashMap<>();
        int i=0;
        for(JsonElement e:items) {
            if(!e.isJsonObject()) throw new IllegalArgumentException("Element must be an object");
            Element el=new Element(e.getAsJsonObject(),i++);
            validateElement(el);
            if (ids.putIfAbsent(el.id, Boolean.TRUE) != null) throw new IllegalArgumentException("Duplicate element id: " + el.id);
            elements.add(el);
        }
        double exit=num(root,"exitDuration",1);
        if(!Double.isFinite(exit)||exit<0||exit>SceneLimits.MAX_DURATION_SECONDS)throw new IllegalArgumentException("Invalid exit duration");
        return new Scene(w,h,elements,bool(root,"debug",false),exit);
    }

    private static void validateElement(Element e) {
        boolean validType = List.of("rect", "text", "sprite", "texture", "image", "logo", "progress_bar", "particles").contains(e.type);
        boolean validAnchor = List.of("top_left", "center").contains(e.anchor);
        boolean validGeometry = Double.isFinite(e.x) && Double.isFinite(e.y) && Math.abs(e.x) <= SceneLimits.MAX_DIMENSION && Math.abs(e.y) <= SceneLimits.MAX_DIMENSION
                && Double.isFinite(e.width) && Double.isFinite(e.height) && e.width >= 0 && e.height >= 0
                && e.width <= SceneLimits.MAX_DIMENSION && e.height <= SceneLimits.MAX_DIMENSION;
        boolean validSprite = Double.isFinite(e.fps) && e.fps >= 0 && e.fps <= 240
                && e.frameWidth >= 0 && e.frameHeight >= 0 && e.frameCount >= 1 && e.frameCount <= 4096;
        boolean validMotion = Double.isFinite(e.parallaxPeriod) && e.parallaxPeriod >= 0 && e.parallaxPeriod <= SceneLimits.MAX_DURATION_SECONDS
                && Double.isFinite(e.parallaxX) && Double.isFinite(e.parallaxY)
                && Double.isFinite(e.scaleX) && Math.abs(e.scaleX) <= 100
                && Double.isFinite(e.scaleY) && Math.abs(e.scaleY) <= 100
                && Double.isFinite(e.rotation) && Math.abs(e.rotation) <= 36000;
        boolean validParticles = Double.isFinite(e.particleSpeed) && e.particleSpeed >= 0 && e.particleSpeed <= 10000
                && Double.isFinite(e.particleSize) && e.particleSize >= 0 && e.particleSize <= SceneLimits.MAX_DIMENSION
                && Double.isFinite(e.particleSpread) && e.particleSpread >= 0 && e.particleSpread <= SceneLimits.MAX_DIMENSION
                && e.particleCount >= 0 && e.particleCount <= 128;
        boolean validText = e.id.length() <= SceneLimits.MAX_TEXT_LENGTH && e.text.length() <= SceneLimits.MAX_TEXT_LENGTH
                && e.texture.length() <= SceneLimits.MAX_TEXT_LENGTH && !e.texture.contains("..")
                && !e.texture.startsWith("/") && !e.texture.contains("\\") && !e.texture.contains(":");
        if (!validType || !validAnchor || !validGeometry || !validSprite || !validMotion || !validParticles || !validText
                || !Double.isFinite(e.opacity) || e.opacity < 0 || e.opacity > 1) {
            throw new IllegalArgumentException("Invalid scene element: " + e.id);
        }
    }

    private static int propertySlot(String property) {
        return switch (property) {
            case "x" -> 0; case "y" -> 1; case "width" -> 2; case "height" -> 3;
            case "scaleX", "scale" -> 4; case "scaleY" -> 5; case "rotation" -> 6;
            case "opacity" -> 7; case "frame" -> 8; default -> -1;
        };
    }

    private static void apply(Element e, int slot, double value) {
        switch (slot) {
            case 0 -> e.currentX = value;
            case 1 -> e.currentY = value;
            case 2 -> e.currentWidth = value;
            case 3 -> e.currentHeight = value;
            case 4 -> e.currentScaleX = value;
            case 5 -> e.currentScaleY = value;
            case 6 -> e.currentRotation = value;
            case 7 -> e.currentOpacity = clamp(value);
            case 8 -> e.currentFrame = (int) value;
            default -> { }
        }
    }
    private static double ease(Easing e,double t){return switch(e){case LINEAR->t;case IN_QUAD->t*t;case OUT_QUAD->t*(2-t);case IN_OUT_QUAD->t<.5?2*t*t:1-Math.pow(-2*t+2,2)/2;case OUT_CUBIC->1-Math.pow(1-t,3);case IN_OUT_CUBIC->t<.5?4*t*t*t:1-Math.pow(-2*t+2,3)/2;case OUT_BACK->{double c=1.70158;yield 1+(c+1)*Math.pow(t-1,3)+c*Math.pow(t-1,2);}};}
    private static double clamp(double n){return Double.isFinite(n)?Math.max(0,Math.min(1,n)):0;}
    private static double progress(double n){return Double.isFinite(n)&&n>=0?Math.max(0,Math.min(1,n)):-1;}
    private static double num(JsonObject o,String k,double d){return o.has(k)?o.get(k).getAsDouble():d;} private static int integer(JsonObject o,String k,int d){return o.has(k)?o.get(k).getAsInt():d;}
    private static boolean bool(JsonObject o,String k,boolean d){return o.has(k)?o.get(k).getAsBoolean():d;} private static String string(JsonObject o,String k,String d){return o.has(k)?o.get(k).getAsString():d;}
    private static <E extends Enum<E>> E enumValue(Class<E> c, String s) {
        try {
            String value = s.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT).replace('-', '_');
            if (c == Easing.class && value.startsWith("EASE_")) value = value.substring(5);
            if (value.equals("INOUT_QUAD")) value = "IN_OUT_QUAD";
            if (value.equals("INOUT_CUBIC")) value = "IN_OUT_CUBIC";
            if (value.equals("PING_PONG")) value = "PINGPONG";
            return Enum.valueOf(c, value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid " + c.getSimpleName() + ": " + s);
        }
    }
}
