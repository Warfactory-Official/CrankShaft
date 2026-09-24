<div style="text-align: center;">
<h1>CrankShaft</h1>
<h6>Unofficial Minecraft 26.2 Vulkan + OpenGL port of Flywheel.</h6>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Warfactory-Official/CrankShaft?style=flat&color=900c3f" alt="License"></a>
<br>
</div>

### About

CrankShaft is an **unofficial port** of [Flywheel](https://github.com/Engine-Room/Flywheel) to Minecraft 26.2,
carrying forward the work of Jozufozu and the Engine-Room team as well as CrankShaft's own 1.12.2 backport. It runs on
NeoForge and Fabric. Its Iris shaderpack support is an adaptation of [Colorwheel](https://github.com/djefrey/Colorwheel):
Colorwheel's shaderpack contract and wavelet OIT, rebuilt on CrankShaft's renderer. It is not affiliated with, endorsed
by, or supported by Flywheel, Colorwheel, or their maintainers. Bugs in CrankShaft are CrankShaft's, not theirs — please
file them here.

Shipped alongside CrankShaft is **Vanillate**, the 26.2 counterpart to Vanillin: instanced rendering for vanilla
entities and block entities via Flywheel. It is bundled inside the CrankShaft jar and is **on by default**. Turn it off
with `"enabled": false` in `config/vanillin.json` (Fabric) or `enabled = false` in `config/vanillin-client.toml`
(NeoForge); it then registers nothing and vanilla renders as usual. A config written by an earlier CrankShaft keeps its
`false`. Individual entities and block entities can be enabled or disabled per entry in the same file.

### Features

- **OpenGL and Vulkan.** Native backends for both of Minecraft 26.2's graphics APIs, selected automatically.
- **Iris shaderpacks.** Instanced visuals render through the active shaderpack's own programs, including its shadow
  pass, instead of being switched off.
- **Order-independent transparency (OIT).** Overlapping translucent instances blend correctly without sorting. Five
  methods are available; see [OIT](#order-independent-transparency).
- **Terrain integration.** Translucent chunk terrain joins the same OIT as instances by default. With Sodium,
  CrankShaft can also cull and draw opaque terrain on the GPU; see [Terrain](#terrain-modes).

### Requirements

- Minecraft 26.2 with NeoForge or Fabric (+ Fabric API), and Java 25+.
- Optional: [Sodium](https://modrinth.com/mod/sodium) 0.9.2 or newer, below 0.10. Needed for the `opaque`/`full`
  terrain modes and the mesh-shader backends.
- Optional: [Iris](https://modrinth.com/mod/iris) for shaderpacks (built against 1.11.4). Requires OpenGL.

### Backends

CrankShaft picks the best supported backend for your hardware. `/flywheel backend` shows or changes it.

| Backend                                              | API    | Selected by default          | Notes                                                                  |
|------------------------------------------------------|--------|------------------------------|------------------------------------------------------------------------|
| `flywheel:vk_indirect`                               | Vulkan | Yes                          | GPU culling + indirect draws.                                          |
| `flywheel:vk_mesh_shader`                            | Vulkan | No                           | Mesh shaders (`VK_EXT_mesh_shader`). Requires Sodium.                  |
| `flywheel:indirect`                                  | OpenGL | Yes, except on Intel GPUs    | GPU culling + indirect draws.                                          |
| `flywheel:gl_mesh_shader`                            | OpenGL | No                           | Mesh shaders, NVIDIA only (`GL_NV_mesh_shader`). Requires Sodium.      |
| `flywheel:instancing`                                | OpenGL | On Intel GPUs; fallback      | Instanced draws without GPU culling.                                   |
| `flywheel:iris_indirect`, `flywheel:iris_instancing` | OpenGL | While a shaderpack is active | Draw through the shaderpack's programs; see [Iris](#iris-shaderpacks). |
| `flywheel:off`                                       | —      | No                           | Disables CrankShaft rendering; vanilla draws everything.               |

If the requested backend is unsupported, CrankShaft falls back to the next one that is. While a shaderpack is active,
choosing a native OpenGL backend selects its `iris_` counterpart.

### Vulkan

Minecraft 26.2 can render with Vulkan: **Options → Video Settings → Graphics API → Vulkan**, then restart the game.
CrankShaft detects the Vulkan renderer and switches to its Vulkan backends; no CrankShaft setting is needed.

On **NeoForge**, also set `earlyWindowControl = false` in `config/fml.toml`. NeoForge's loading screen is
OpenGL-only, and the game fails to start on Vulkan without this change.

Rendering features are the same on both APIs; only shaderpack support requires OpenGL.

Mods that replace the world renderer (for example the path tracer Caustica) take over drawing: CrankShaft suspends
itself while they do, and entities and block entities appear through their vanilla renderers.

### Iris shaderpacks

With Iris and a shaderpack enabled, CrankShaft switches to its `iris_` backends and renders instances through the
pack's own programs, shadows included. Nothing needs configuring.

- Packs that implement [Colorwheel](https://github.com/djefrey/Colorwheel)'s shaderpack contract (`clrwl_` programs
  and `colorwheel.properties`) use it directly, including its OIT where the pack enables it.
- Bundled adapters fill gaps in specific packs (see below). An adapter checks the pack's actual shader sources, not its
  file name or version. If a pack has changed, the adapter is skipped as a whole and the pack falls back to the shared
  path.
- Other packs render instances through their standard programs, without OIT.
- Under a shaderpack, OIT follows the pack's own settings; `/flywheel oit mode` applies only to the non-Iris backends.

Tested shaderpacks:

| Shaderpack               | Version                  | Colorwheel contract | Bundled adapter | OIT                                                 |
|--------------------------|--------------------------|---------------------|-----------------|-----------------------------------------------------|
| Complementary Reimagined | r5.9.3                   | Yes                 | Yes             | Yes                                                 |
| Complementary Unbound    | r5.9.3                   | Yes                 | Yes             | Yes                                                 |
| Solas                    | V3.7b                    | Yes                 | Yes             | Yes                                                 |
| BSL                      | v10.1.5                  | No                  | Yes             | Yes                                                 |
| MakeUp UltraFast         | 9.5e                     | No                  | Yes             | Yes                                                 |
| Sundial                  | Alpha Build 2026-08-28   | Yes                 | Yes             | Yes, multi-layer (see below)                        |
| IterationRP              | Alpha 0.8.28             | Yes                 | Yes             | No (the pack disables it)                           |
| Bliss                    | v2.1.2 (Chocapic13 edit) | No                  | Yes             | Yes                                                 |
| Sildur's Vibrant Shaders | v2.01 Extreme            | No                  | Yes             | Yes                                                 |
| Photon                   | v1.3b                    | No                  | Yes             | Yes                                                 |

Instances drawn through a pack can differ slightly from what the pack's own renderer would produce.

Sundial's multi-layer OIT replays the pack's deferred lighting once per translucent layer. Its GPU cost and memory
follow the translucency on screen and drop to near zero without it. `-Dcrankshaft.iris.oit.deferred=false` turns it
off. Under Sildur's underwater fog, translucents seen through water are tinted slightly differently from the pack's
own sorted rendering.

Under a shaderpack, any [terrain mode](#terrain-modes) but `off` also draws chunk terrain through the pack's own
terrain programs with GPU culling.

Experimental options, **off by default**, are enabled with Java arguments in your launcher:

| Argument                              | Effect                                                                                                                               |
|---------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `-Dcrankshaft.iris.mesh=true`         | With a terrain mode other than `off`: mesh-shader terrain. `flywheel:iris_mesh_shader` is then selected on supported NVIDIA GPUs.    |
| `-Dcrankshaft.iris.mesh.direct=true`  | With both of the above: opaque terrain skips task-shader culling, lowering its overhead.                                             |

### Commands

Commands are client-side; settings they change are saved to the config file. Tab completion lists every option.

| Command                                                    | Effect                                                                                                                                                 |
|------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/flywheel backend`                                        | Show the active backend.                                                                                                                               |
| `/flywheel backend <id>` / `/flywheel backend DEFAULT`     | Use a specific backend / let CrankShaft choose. Reloads renderers.                                                                                     |
| `/flywheel terrain off\|translucent\|opaque\|full`         | Terrain integration mode; see below.                                                                                                                   |
| `/flywheel oit`                                            | Show the OIT method, layer budgets and weather mode.                                                                                                   |
| `/flywheel oit mode auto\|wavelet\|kbuffer\|mlab\|abuffer` | Choose the OIT method; see below.                                                                                                                      |
| `/flywheel oit layers <0-32>`                              | Layer budget of the active insert method (`0` = its preset).                                                                                           |
| `/flywheel oit reset`                                      | Restore all layer budgets to their presets.                                                                                                            |
| `/flywheel oit exactweather true\|false`                   | `true`: rain and snow sort per fragment against other translucents. `false` (default): weather is blended as one layer, which is much cheaper in rain. |
| `/flywheel lightSmoothness <mode>`                         | `flat`, `tri_linear`, `smooth` (default) or `smooth_inner_face_corrected`.                                                                             |
| `/flywheel limitUpdates on\|off`                           | Update distant instances less often (default on).                                                                                                      |
| `/flywheel debug info`                                     | Version, backend, settings and visual/instance counts; useful for bug reports.                                                                         |
| `/flywheel debug gpuTimer on\|off\|once\|summary`          | Per-pass GPU timings.                                                                                                                                  |

#### Order-independent transparency

| Mode             | Method                                                                                     |
|------------------|--------------------------------------------------------------------------------------------|
| `auto` (default) | `mlab` where the GPU supports fragment-shader interlock, otherwise `wavelet`.              |
| `wavelet`        | Flywheel's multi-pass wavelet transmittance. Works everywhere.                             |
| `kbuffer`        | Stores the first *K* fragments to reach each pixel (preset 4), then sorts them.            |
| `mlab`           | Multi-layer alpha blending: *K* sorted layers (preset 8). Needs fragment-shader interlock. |
| `abuffer`        | Per-pixel fragment lists, resolving the nearest *N* (preset 16). Highest memory use.       |

`wavelet` is always an approximation. `kbuffer`, `mlab` and `abuffer` are single-pass *insert* methods and are exact
while no pixel has more translucent fragments than the layer budget (*K*, or *N* for `abuffer`). Past the budget,
`kbuffer` drops the extra fragments regardless of depth, `mlab` merges its farthest layers and `abuffer` drops the
farthest. `abuffer` is also only exact while its fragment pool, 8 per screen pixel across the whole frame, doesn't run
out. More layers keep deeper stacks of stained-glass exact at a higher GPU cost.

On OpenGL the insert methods run on the GPU-driven backends (`indirect`, `gl_mesh_shader`); `instancing` always uses
`wavelet`. Without fragment-shader interlock, `mlab` falls back to `wavelet`.

#### Terrain modes

| Mode                    | Opaque terrain         | Translucent terrain (water, glass, ice) | Requires                     |
|-------------------------|------------------------|-----------------------------------------|------------------------------|
| `off`                   | Vanilla / Sodium       | Vanilla / Sodium                        | —                            |
| `translucent` (default) | Vanilla / Sodium       | CrankShaft OIT, blended with instances  | Any backend, Sodium optional |
| `opaque`                | CrankShaft, GPU-culled | Sodium                                  | Sodium, GPU-driven backend   |
| `full`                  | CrankShaft, GPU-culled | CrankShaft OIT, blended with instances  | Sodium, GPU-driven backend   |

GPU-driven backends are `indirect`, `gl_mesh_shader`, `vk_indirect` and `vk_mesh_shader`. On other backends or without
Sodium, `opaque` behaves like `off` and `full` like `translucent`.

With a shaderpack loaded, any mode but `off` additionally hands chunk terrain to the pack's terrain programs, which is
what lets engine translucents and pack-drawn terrain sort against each other.

### Configuration

Commands write the same files you can edit by hand: `config/crankshaft.json` on Fabric, `config/flywheel-client.toml`
on NeoForge. Settings: `backend`, `limitUpdates`, `workerThreads`, `useCommonPool`, `concurrentExtraction`, and under
`flw_backends`: `lightSmoothness`, `terrain` and the OIT settings.

`concurrentExtraction` (default on) extracts entity and block entity render states on Flywheel's worker threads, for vanilla's renderers
and mod renderers implementing `ConcurrentRenderStateExtraction`, with or without a backend. Turn it off if a mod that
hooks entity rendering misbehaves.

### Instancing

Flywheel provides an alternate, unified path for entity and block entity rendering that takes advantage of GPU
instancing. Flywheel gives the developer the flexibility to define their instance formats and write custom shaders to
ingest that data.

To accommodate the developer and leave more in the hands of the engine, Flywheel provides a custom shader loading and
templating system to hide the details of the CPU/GPU interface.

### Building

```
gradlew build
```

### Getting Started (For Developers)

Add the following repo and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://repo.warfactory.co/releases")
}

dependencies {
    // NeoForge
    implementation("dev.engine_room:crankshaft-neoforge:1.5.0+mc26.2")
    // Fabric
    implementation("dev.engine_room:crankshaft-fabric:1.5.0+mc26.2")
}
```

For a list of available CrankShaft versions, you can check
the [Fabric](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-fabric/) and
[NeoForge](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-neoforge/) Maven directories.

If you want the bleeding edge:

```kotlin
repositories {
    maven("https://repo.warfactory.co/snapshots")
}

implementation("dev.engine_room:crankshaft-fabric:1.5.0+mc26.2-SNAPSHOT")
```

### License

- `:meshlet`: LGPL-3.0-only
- Everything else: MIT

Third-party code is credited in `THIRD_PARTY_NOTICES`; `:meshlet` and `:iris` carry their own.
