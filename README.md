<div style="text-align: center;">
<h1>CrankShaft</h1>
<h6>Unofficial Minecraft 26.2 Vulkan + OpenGL port of Flywheel.</h6>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Warfactory-Official/CrankShaft?style=flat&color=900c3f" alt="License"></a>
<br>
</div>

### About

CrankShaft is an **unofficial port** of [Flywheel](https://github.com/Engine-Room/Flywheel) to Minecraft 26.2 for
NeoForge and Fabric, building on the work of Jozufozu and the Engine-Room team and on CrankShaft's 1.12.2 backport.
Its shaderpack support adapts [Colorwheel](https://github.com/djefrey/Colorwheel)'s shaderpack contract and wavelet
OIT. It is not affiliated with or supported by Flywheel, Colorwheel or their maintainers; please report bugs here.

The bundled **Vanillate** (our counterpart to Vanillin) instances vanilla entities and block entities. It is on
by default; set `enabled` to false, or disable individual entries, in `config/vanillate.json` (Fabric) or
`config/vanillate-client.toml` (NeoForge).

### Features

- Native OpenGL and Vulkan backends, selected automatically.
- Iris shaderpacks: instances render through the pack's own programs, shadow pass included. Multi-layer OIT for
  Sundial, IterationRP and SEUS PTGI; see [Iris](#iris-shaderpacks).
- Order-independent transparency for translucent instances and terrain.
- With Sodium, opaque terrain can also be GPU-culled and drawn by CrankShaft.

### Requirements

- Minecraft 26.2, NeoForge or Fabric (+ Fabric API), Java 25+.
- Any GPU and driver that run vanilla 26.2; features your hardware lacks fall back (see [Backends](#backends)).
- Optional: [Sodium](https://modrinth.com/mod/sodium) 0.9.2+, for the `opaque`/`full` terrain modes and the
  mesh-shader backends.
- Optional: [Iris](https://modrinth.com/mod/iris) (1.11.4+) for shaderpacks. OpenGL only.

### Backends

`/flywheel backend` shows or changes the backend; by default CrankShaft picks the best one supported.

| Backend                                              | API    | Default                      | Notes                                                 |
|------------------------------------------------------|--------|------------------------------|-------------------------------------------------------|
| `flywheel:vk_indirect`                               | Vulkan | Yes                          | GPU culling + indirect draws.                         |
| `flywheel:vk_mesh_shader`                            | Vulkan | No                           | `VK_EXT_mesh_shader`. Requires Sodium.                |
| `flywheel:indirect`                                  | OpenGL | Yes, except on Intel         | GPU culling + indirect draws.                         |
| `flywheel:gl_mesh_shader`                            | OpenGL | No                           | `GL_NV_mesh_shader` (NVIDIA). Requires Sodium.        |
| `flywheel:instancing`                                | OpenGL | On Intel; fallback           | Instanced draws, no GPU culling.                      |
| `flywheel:iris_indirect`, `flywheel:iris_instancing` | OpenGL | While a shaderpack is active | Draw through the pack; see [Iris](#iris-shaderpacks). |
| `flywheel:off`                                       | —      | No                           | Vanilla draws everything.                             |

An unsupported backend falls back to the next supported one; under a shaderpack, native OpenGL backends map to their
`iris_` counterparts. If shaders fail to compile or link, CrankShaft drops the failing optional feature or falls back
a backend, down to `flywheel:off`, and logs the cause. `-Dcrankshaft.shader.strict=true` crashes instead (useful for
bug reports).

### Vulkan

Select **Options → Video Settings → Graphics API → Vulkan** and restart; CrankShaft follows automatically. On
NeoForge, also set `earlyWindowControl = false` in `config/fml.toml`, or the game fails to start. Shaderpacks need
OpenGL; everything else works on both APIs.

Mods that replace the world renderer (e.g. Caustica) suspend CrankShaft while active; entities and block entities
then use their vanilla renderers.

### Iris shaderpacks

With a shaderpack active, CrankShaft switches to its `iris_` backends; nothing needs configuring.

- Packs implementing Colorwheel's contract (`clrwl_` programs, `colorwheel.properties`) use it directly, including
  its OIT.
- Bundled adapters add OIT and fixes for the packs below. They match the pack's shader sources, not its file name:
  other versions or edited packs skip the adapter.
- Other packs render instances through their standard programs, without OIT.
- OIT follows the pack; `/flywheel oit mode` applies only to non-Iris backends.
- Any [terrain mode](#terrain-modes) but `off` also draws chunk terrain through the pack's terrain programs, GPU-culled,
  so it sorts against instances.

| Shaderpack                         | Tested version              | Colorwheel contract | OIT                   |
|------------------------------------|-----------------------------|---------------------|-----------------------|
| Complementary Reimagined / Unbound | r5.9.3                      | Yes                 | Wavelet               |
| Complementary Euphoria             | r5.9.3 + Patches 1.10.5     | Yes                 | Wavelet               |
| Solas                              | V3.7b                       | Yes                 | Wavelet               |
| BSL                                | v10.1.8                     | No                  | Wavelet               |
| MakeUp UltraFast                   | 9.5f                        | No                  | Wavelet               |
| Bliss                              | v2.1.2 (Chocapic13 edit)    | No                  | Wavelet               |
| Sildur's Vibrant Shaders           | v2.02 Extreme               | No                  | Wavelet               |
| Photon                             | v1.3b                       | No                  | Wavelet               |
| Hysteria                           | v1.2.2                      | No                  | Wavelet               |
| Sundial                            | Alpha Build 2026-09-25      | Yes                 | Deferred, multi-layer |
| IterationRP                        | Alpha 0.8.29                | Yes                 | Deferred, multi-layer |
| SEUS PTGI                          | HRR Test 2.1, HRR 3         | No                  | Deferred, multi-layer |
| SEUS PTGI GFME                     | v1.22 RC1                   | No                  | Deferred, multi-layer |

Instances drawn through a pack can differ slightly from the pack's own rendering. Under Sildur's underwater fog,
translucents behind water are tinted slightly differently.

**Deferred multi-layer OIT** captures translucent fragments, sorts them by depth and re-runs the pack's own lighting,
reflection and refraction passes per layer. Overlapping translucent layers cost extra GPU time and memory. Unsupported
shader variants and frames exceeding capture capacity (over 64 layers at a pixel, or sudden growth) keep the pack's
native ordering; fallbacks are logged. `-Dcrankshaft.iris.oit.deferred=false` disables it;
`-Dcrankshaft.iris.oit.iteration=false` disables only IterationRP's.

Experimental, **off by default**:

| Java argument                        | Effect                                                                                             |
|--------------------------------------|----------------------------------------------------------------------------------------------------|
| `-Dcrankshaft.iris.mesh=true`        | Mesh-shader terrain under a terrain mode but `off`; selects `flywheel:iris_mesh_shader` on NVIDIA. |
| `-Dcrankshaft.iris.mesh.direct=true` | With the above: opaque terrain skips task-shader culling, lowering overhead.                       |

### Commands

Client-side; changes are saved to the config. Tab completion lists every option.

| Command                                                    | Effect                                                                              |
|------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `/flywheel backend [<id>\|DEFAULT]`                        | Show or set the backend. Reloads renderers.                                         |
| `/flywheel terrain off\|translucent\|opaque\|full`         | [Terrain mode](#terrain-modes).                                                     |
| `/flywheel oit`                                            | Show OIT method, layer budgets and weather mode.                                    |
| `/flywheel oit mode auto\|wavelet\|kbuffer\|mlab\|abuffer` | [OIT method](#order-independent-transparency).                                      |
| `/flywheel oit layers <0-32>`                              | Layer budget of the active insert method (`0` = preset).                            |
| `/flywheel oit reset`                                      | Restore all layer budgets to presets.                                               |
| `/flywheel oit exactweather true\|false`                   | Sort rain/snow per fragment (`true`) or blend weather as one cheap layer (default). |
| `/flywheel lightSmoothness <mode>`                         | `flat`, `tri_linear`, `smooth` (default) or `smooth_inner_face_corrected`.          |
| `/flywheel limitUpdates on\|off`                           | Update distant instances less often (default on).                                   |
| `/flywheel debug info`                                     | Version, backend, settings and instance counts, for bug reports.                    |
| `/flywheel debug gpuTimer on\|off\|once\|summary`          | Per-pass GPU timings.                                                               |

#### Order-independent transparency

| Mode             | Method                                                                  | Past the layer budget   |
|------------------|-------------------------------------------------------------------------|-------------------------|
| `auto` (default) | `mlab` with fragment-shader interlock, else `wavelet`.                  | As resolved             |
| `wavelet`        | Multi-pass wavelet transmittance; always approximate. Works everywhere. | —                       |
| `kbuffer`        | First *K* fragments per pixel (preset 4), sorted.                       | Drops extras, any depth |
| `mlab`           | *K* sorted layers (preset 8). Needs fragment-shader interlock.          | Merges farthest layers  |
| `abuffer`        | Per-pixel lists, nearest *N* resolved (preset 16). Most memory.         | Drops farthest          |

The insert methods (`kbuffer`, `mlab`, `abuffer`) are exact within their layer budget; `abuffer` also needs its
pool of 8 fragments per screen pixel not to run out. On OpenGL they need `indirect` or `gl_mesh_shader`;
`instancing`, and `mlab` without interlock, use `wavelet`.

#### Terrain modes

| Mode                    | Opaque terrain         | Translucent terrain (water, glass, ice) | Requires                   |
|-------------------------|------------------------|-----------------------------------------|----------------------------|
| `off`                   | Vanilla / Sodium       | Vanilla / Sodium                        | —                          |
| `translucent` (default) | Vanilla / Sodium       | CrankShaft OIT, with instances          | —                          |
| `opaque`                | CrankShaft, GPU-culled | Sodium                                  | Sodium, GPU-driven backend |
| `full`                  | CrankShaft, GPU-culled | CrankShaft OIT, with instances          | Sodium, GPU-driven backend |

GPU-driven backends: `indirect`, `gl_mesh_shader`, `vk_indirect`, `vk_mesh_shader`. Otherwise `opaque` acts as `off`
and `full` as `translucent`.

### Configuration

`config/crankshaft.json` (Fabric) or `config/crankshaft-client.toml` (NeoForge): `backend`, `limitUpdates`,
`workerThreads`, `useCommonPool`, `concurrentExtraction`, and under `flw_backends` `lightSmoothness`, `terrain` and
the OIT settings.

`concurrentExtraction` (default on) extracts entity and block entity render states on worker threads, for vanilla
renderers and mod renderers implementing `ConcurrentRenderStateExtraction`. Turn it off if a mod hooking entity
rendering misbehaves.

#### Shader caches

On OpenGL, generated shader sources and linked programs are cached under `cache/crankshaft/`, per driver, up to 1 GiB.
Programs are cached as they compile, so an interrupted launch keeps its progress; compiles longer than a quarter
second show a progress bar under the loading bar. Deleting the folder is safe. `-Dcrankshaft.gl.programCache=false`
and `-Dcrankshaft.glsl.sourceCache=false` disable the caches.

### Building

```
gradlew build
```

### Getting Started (For Developers)

```kotlin
repositories {
    maven("https://repo.warfactory.co/releases")
    // Snapshots (append -SNAPSHOT to the version): maven("https://repo.warfactory.co/snapshots")
}

dependencies {
    // NeoForge
    implementation("dev.engine_room:crankshaft-neoforge:1.5.10+mc26.2")
    // Fabric
    implementation("dev.engine_room:crankshaft-fabric:1.5.10+mc26.2")
}
```

Available versions: [Fabric](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-fabric/),
[NeoForge](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-neoforge/).

### License

- `:meshlet`: LGPL-3.0-only
- Everything else: MIT

Third-party code is credited in `THIRD_PARTY_NOTICES`; `:meshlet` and `:iris` carry their own.
