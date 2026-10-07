# Custom Loading Screen

Configure the Minecraft loading screen with JSON. Requires Minecraft 1.21.1, NeoForge 21.1.209, and FML 4.0.41.

## Build and run

Use Java 21.

```sh
./gradlew build
./gradlew :1.21.1-neoforge:runClient
./gradlew :1.21.1-neoforge:buildAndCollect
```

The client run uses the root `run` directory. `buildAndCollect` puts the installable and sources jars in `build/libs/0.2.0/`.

Install `customloadingscreen-0.2.0+1.21.1-neoforge.jar` in the instance's `mods` folder.

## Publish

Publishing uploads files to the selected platform. The project IDs are configured. Set the platform token as `MODRINTH_TOKEN` or `CURSEFORGE_TOKEN`, or use the Gradle properties `publish.modrinth_token` or `publish.curseforge_token`.

```sh
./gradlew :1.21.1-neoforge:publishModrinth
./gradlew :1.21.1-neoforge:publishCurseforge
./gradlew :1.21.1-neoforge:publishMods
```

The first two commands upload to one platform. `publishMods` uploads to both. Each task checks the project ID and token before upload.

## Enable the provider

Set these values in `config/fml.toml` before starting Minecraft. For `runClient`, use `run/config/fml.toml`:

```toml
earlyWindowControl = true
earlyWindowProvider = "customloadingscreen"
```

## Scene and image files

The [default scene](src/main/resources/assets/customloadingscreen/startup/scene.json) is copied to `config/customloadingscreen/scene.json` on first start. Edit the config copy to customize the screen.

Put custom images in `config/customloadingscreen/assets/`. A texture value such as `logo.png` loads from this folder first. If that file is absent, the loader checks the bundled folder `assets/customloadingscreen/startup/`. The default scene shows its text value when its logo image is missing.

The scene file can be up to 1 MiB. Each image can be up to 16 MiB and 4096 by 4096 pixels. Texture paths are relative and cannot contain `..`, start with `/`, or contain a backslash or colon.

The early display uses a 854 by 480 render target at up to 60 FPS. It scales the virtual canvas to fit. Empty space uses a black letterbox. Text uses FML's built-in font. Use basic ASCII text. The renderer does not support mouse input, mouse parallax, or animated tint.

The game uses a second, hidden window while Minecraft initializes. The early display keeps rendering in its own OpenGL context during this time. After initialization, the game window shows the captured loading screen and fades to the menu. In fullscreen mode, Minecraft applies fullscreen after this fade.

## Scene JSON

Root keys:

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvasWidth` | integer | `1920` | Width of the virtual canvas. Range: 1–16384. |
| `canvasHeight` | integer | `1080` | Height of the virtual canvas. Range: 1–16384. |
| `debug` | boolean | `false` | Show stage, progress, FPS, elapsed time, and active animations. |
| `exitDuration` | number | `1` | Seconds for the custom scene fade after `COMPLETE`. Range: 0–3600. The renderer evaluates this fade when it captures the final frame. NeoForge then fades that still frame to the menu over about 0.25 seconds. |
| `elements` | array | `[]` | Ordered render elements. The engine sorts them by `layer`. Limit: 128. |

When Fadeless is installed, the loading overlay is restored during NeoForge's crossfade so the custom screen can finish its transition.

Element keys. Unknown types or invalid values stop scene loading.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | string | `element_<index>` | Unique element name. Up to 256 characters. |
| `type` | string | `rect` | `rect`, `text`, `sprite`, `texture`, `image`, `logo`, `progress_bar`, or `particles`. `texture`, `image`, `logo`, and `sprite` draw an image. |
| `layer` | integer | `0` | Draw order. Lower values draw first. |
| `x`, `y` | number | `0` | Position on the virtual canvas. |
| `width`, `height` | number | `0` | Element size. Range: 0–16384. |
| `anchor` | string | `top_left` | `top_left` keeps the origin at the upper-left. `center` centers text using its measured width and a 24-pixel font line box; other elements use their width and height box. |
| `scaleX`, `scaleY` | number | `1` | Scale on each axis. Absolute value is limited to 100. |
| `rotation` | number | `0` | Rotation in degrees. Absolute value is limited to 36000. |
| `opacity` | number | `1` | Base opacity from 0 to 1. |
| `tint` | string | `#FFFFFFFF` | Color in `#RRGGBBAA` form. Tint cannot be animated. |
| `texture` | string | empty | Relative image path for `sprite` or `logo` elements. |
| `text` | string | empty | Text value. It is the fallback when an image texture is missing. |
| `frameWidth`, `frameHeight` | integer | `0` | Frame size in pixels for a sprite sheet. |
| `frameCount` | integer | `1` | Number of frames. Range: 1–4096 and limited by the image sheet. Frames follow rows from left to right. Sampling clamps at the image edge. |
| `fps` | number | `0` | Sprite frames per second. Range: 0–240. Zero disables frame animation. |
| `spriteLoop` | boolean | `true` | Repeat the sprite frames. `false` holds the last frame. The frame index wraps when true and clamps to the last available frame when false. |
| `parallaxX`, `parallaxY` | number | `0` | Add a sine-wave position offset in pixels. |
| `parallaxPeriod` | number | `4` | Seconds for one parallax cycle. Zero disables parallax. Maximum: 3600. |
| `count` | integer | `32` | Particle count. Range: 0–128. |
| `speed` | number | `20` | Particle movement speed. Maximum: 10000. |
| `size` | number | `3` | Particle size in pixels. Maximum: 16384. |
| `spread` | number | `1` | Particle movement spread in pixels. Maximum: 16384. |
| `animations` | array | `[]` | Property animations for this element. Limit: 32. |

The `particles` type uses the element position and size as its particle field. Its `tint`, `opacity`, `count`, `speed`, `size`, and `spread` keys control the particle color and motion.

Integer fields reject fractional values and values outside their documented range.

The loader caches successful decoded textures up to a total of 128 MiB of RGBA pixels. It rejects an image that exceeds this budget, and uses the scene fallback when an image is missing, rejected, or fails to upload.

The renderer requests scene images before its first frame. A background worker reads and decodes them; OpenGL uploads them on the render thread. The scene fallback appears while an image loads.

When overall progress is available, a `progress_bar` uses its current animated width multiplied by overall progress. If progress is unknown, it uses its current width unchanged.

## Animations

An animation accepts these keys:

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `name` | string | empty | Unique name within its element. Use it as another animation's `onComplete` target. |
| `property` | string | `y` | `x`, `y`, `width`, `height`, `scale`, `scaleX`, `scaleY`, `rotation`, `opacity`, or `frame`. `scale` changes both axes. |
| `from`, `to` | number | `0` | Start and end values. Absolute value is limited to 16384. |
| `duration` | number | `1` | Time-based animation length in seconds. Range: greater than 0 to 3600. |
| `delay` | number | `0` | Time-based delay in seconds. Range: 0–3600. |
| `easing` | string | `linear` | Easing curve. See supported values below. |
| `playback` | string | `once` | `once`, `loop`, or `pingpong` (`ping-pong` is also accepted). This controls time-based animations. |
| `trigger` | string | `time` | `time`, `stage`, `stage_progress`, or `overall_progress`. |
| `stage` | string | none | Required for a `stage` trigger. |
| `threshold` | number | `0` | Start a progress-triggered animation when its progress reaches this value. Range: 0–1. |
| `onComplete` | string | empty | Start the named animation when this animation finishes. Chains must name existing animations and cannot contain cycles. |

Supported easing values are `linear`, `inQuad`, `outQuad`, `inOutQuad`, `outCubic`, `inOutCubic`, and `outBack`. Names such as `easeInQuad`, `easeOutCubic`, and `easeOutBack` also work. Underscore and hyphen forms work too, such as `in_out_quad` and `ping-pong`.

Stage names are `BOOT`, `MOD_DISCOVERY`, `MOD_CONSTRUCTION`, `COMMON_SETUP`, `CLIENT_SETUP`, `RESOURCE_LOADING`, `FINALIZING`, and `COMPLETE`. Lifecycle events report mod construction, common setup, client setup, finalizing, and completion. The adapter reads FML progress meters for discovery and resource loading. It advances through skipped stages in order.

An `overall_progress` or `stage_progress` trigger maps the progress value from 0–1 between `from` and `to`, with easing. Unknown progress is `-1`. The engine holds the configured base value until progress is available, and holds its last driven value if progress later becomes unknown. Stage progress becomes unknown when its meter disappears. Overall progress keeps the highest estimate seen so far.

Progress is an estimate from available FML progress meters and the current startup stage. NeoForge does not expose one exact percentage for all startup work. Stages without a progress meter remain indeterminate.

If the scene fails to load, the provider keeps the normal FML screen. If rendering fails, it shows a solid-color screen. Development checks with NeoForge 21.1.209 completed in windowed and fullscreen modes. During a 5-second game-thread pause, the startup renderer produced 300 frames. The packaged renderer also completed startup in a modpack with Connector and Fadeless. The 0.25-second menu fade has built successfully; its visual duration has not been checked in-game.

## Example

The logo moves into place at `MOD_CONSTRUCTION`, then moves up and down:

```json
{
  "id": "logo",
  "type": "sprite",
  "texture": "logo.png",
  "text": "My Mod",
  "x": 960,
  "y": 450,
  "width": 720,
  "height": 100,
  "anchor": "center",
  "animations": [
    {
      "name": "intro",
      "property": "y",
      "from": -150,
      "to": 450,
      "duration": 1.1,
      "stage": "MOD_CONSTRUCTION",
      "trigger": "stage",
      "easing": "outCubic",
      "onComplete": "idle"
    },
    {
      "name": "idle",
      "property": "y",
      "from": 450,
      "to": 442,
      "duration": 2.4,
      "playback": "pingpong"
    }
  ]
}
```

## API references

The provider follows the `earlyWindowProvider` flow used by [SimpleCustomEarlyLoading](https://github.com/lukaskabc/SimpleCustomEarlyLoading). Related APIs:

- FML [`ImmediateWindowProvider`](https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/neoforgespi/earlywindow/ImmediateWindowProvider.java)
- FML [`DisplayWindow`](https://github.com/neoforged/FancyModLoader/blob/1.21.1/earlydisplay/src/main/java/net/neoforged/fml/earlydisplay/DisplayWindow.java)
- NeoForge [`NeoForgeLoadingOverlay`](https://github.com/neoforged/NeoForge/blob/21.1.209/src/main/java/net/neoforged/neoforge/client/loading/NeoForgeLoadingOverlay.java)
