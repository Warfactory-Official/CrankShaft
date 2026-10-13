<div style="text-align: center;">
<h1>CrankShaft</h1>
<h6>Unofficial Minecraft 26.2 and 26.3 Vulkan + OpenGL port of Flywheel.</h6>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Warfactory-Official/CrankShaft?style=flat&color=900c3f" alt="License"></a>
<br>
</div>

### About

CrankShaft is an **unofficial port** of [Flywheel](https://github.com/Engine-Room/Flywheel) to Minecraft 26.2 and 26.3
for
NeoForge and Fabric, building on the work of Jozufozu and the Engine-Room team and on CrankShaft's 1.12.2 backport.
Its shaderpack support adapts [Colorwheel](https://github.com/djefrey/Colorwheel)'s shaderpack contract and wavelet
OIT. It is not affiliated with or supported by Flywheel, Colorwheel or their maintainers; please report bugs here.

`multiversion` is the main development branch for all supported Minecraft versions. The `26.2` branch is retained
for maintenance of the earlier single-version build.

The bundled **Vanillate** (our counterpart to Vanillin) instances vanilla entities and block entities. It is on
by default; set `enabled` to false, or disable individual entries, in `config/vanillate.json` (Fabric) or
`config/vanillate-client.toml` (NeoForge).

Minecraft 26.3 supports native Improved Transparency on OpenGL and Vulkan with the selected CrankShaft OIT
algorithm; instancing retains its wavelet-only contract. Changing transparency or the OIT algorithm completes
shader preparation before rendering resumes, which can cause a one-time pause. Sodium terrain and the mesh
backends support both graphics APIs. On Minecraft 26.3, Distant Horizons 3.3.4 is verified on OpenGL and Vulkan,
including native
Improved Transparency. Its Vulkan implementation still reports empty uploads and unreleased resources; the
compatibility cleanup covers the view and sampler borrowing CrankShaft's fade mask.

Iris uses the shaderpack's own translucency contract and preserves Iris's policy of disabling native Improved
Transparency while a pack is active. The shaderpack-version table below covers Minecraft 26.2 testing and the
26.3 indirect guest with full terrain. Deterministic ID, shadow and OIT probes also verify the 26.3 guest contract.

The 26.3 development pin uses NeoForge 26.3.0.64-beta while Sodium's
[NeoForge 0.65+ compatibility fix](https://github.com/CaffeineMC/sodium/pull/3948) is pending. Polytone's 26.3 Fabric
alpha passed compatibility checks; its NeoForge alpha currently has an upstream startup failure.

### Features

- Native OpenGL and Vulkan backends, selected automatically.
- Iris shaderpacks: instances render through the pack's own programs, shadow pass included. Multi-layer OIT for
  Sundial, IterationRP and SEUS PTGI; see [Iris](#iris-shaderpacks).
- Order-independent transparency for translucent instances and terrain.
- With Sodium, opaque terrain can also be GPU-culled and drawn by CrankShaft.

### Requirements

- Minecraft 26.2 or 26.3, NeoForge or Fabric (+ Fabric API), Java 25+.
- Any GPU and driver that run the matching vanilla version; features your hardware lacks fall back
  (see [Backends](#backends)).
- Optional: [Sodium](https://modrinth.com/mod/sodium), for the `opaque`/`full` terrain modes and mesh-shader backends:
  0.9.2 on 26.2, or 0.9.3-alpha.1 on 26.3.
- Optional: [Iris](https://modrinth.com/mod/iris) for shaderpacks: 1.11.4 on 26.2, or 1.11.7 on 26.3 (OpenGL).
  Minecraft 26.3 also supports IMS's experimental Iris 1.12 build on OpenGL and Vulkan; see [Iris](#iris-shaderpacks).

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
| `flywheel:iris_vk_indirect`                          | Vulkan | With experimental Iris 26.3  | GPU culling and batched draws through the pack.       |
| `flywheel:off`                                       | —      | No                           | Vanilla draws everything.                             |

An unsupported backend falls back to the next supported one; under a shaderpack, native OpenGL backends map to their
`iris_` counterparts. Optional availability failures disable the affected feature or select the next supported
backend, down to `flywheel:off`, and log the cause. Unexpected native API or compiler failures remain fatal.
`-Dcrankshaft.shader.strict=true` makes recoverable availability failures fatal too (useful for bug reports).

### Vulkan

Select **Options → Video Settings → Graphics API → Vulkan** and restart; CrankShaft follows automatically. On
NeoForge, also set `earlyWindowControl = false` in `config/fml.toml`, or the game fails to start. Vulkan shaderpacks
require the experimental Iris 26.3 build. Mainline Iris uses OpenGL.

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

The table below records the mainline Iris pack matrix. It does not establish experimental Iris compatibility
for every listed pack; experimental checks cover Complementary Reimagined, Photon, Sundial and SEUS PTGI HRR Test 2.1 /
HRR 3.

| Shaderpack                         | Tested version           | Colorwheel contract | OIT                   |
|------------------------------------|--------------------------|---------------------|-----------------------|
| Complementary Reimagined / Unbound | r5.9.3                   | Yes                 | Wavelet               |
| Complementary Euphoria             | r5.9.3 + Patches 1.10.5  | Yes                 | Wavelet               |
| Solas                              | V3.7b                    | Yes                 | Wavelet               |
| BSL                                | v10.1.8                  | No                  | Wavelet               |
| MakeUp UltraFast                   | 9.5f                     | No                  | Wavelet               |
| Bliss                              | v2.1.2 (Chocapic13 edit) | No                  | Wavelet               |
| Sildur's Vibrant Shaders           | v2.02 Extreme            | No                  | Wavelet               |
| Photon                             | v1.3b                    | No                  | Wavelet               |
| Hysteria                           | v1.2.2                   | No                  | Wavelet               |
| Sundial                            | Alpha Build 2026-09-25   | Yes                 | Deferred, multi-layer |
| IterationRP                        | Alpha 0.8.29             | Yes                 | Deferred, multi-layer |
| SEUS PTGI                          | HRR Test 2.1, HRR 3      | No                  | Deferred, multi-layer |
| SEUS PTGI GFME                     | v1.22 RC1                | No                  | Deferred, multi-layer |

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

| Command                                                    | Effect                                                                      |
|------------------------------------------------------------|-----------------------------------------------------------------------------|
| `/flywheel backend [<id>\|DEFAULT]`                        | Show or set the backend. Reloads renderers.                                 |
| `/flywheel terrain off\|translucent\|opaque\|full`         | [Terrain mode](#terrain-modes).                                             |
| `/flywheel oit`                                            | Show OIT method, layer budgets and (26.2) weather mode.                     |
| `/flywheel oit mode auto\|wavelet\|kbuffer\|mlab\|abuffer` | [OIT method](#order-independent-transparency).                              |
| `/flywheel oit layers <0-32>`                              | Layer budget of the active insert method (`0` = preset).                    |
| `/flywheel oit reset`                                      | Restore all layer budgets to presets.                                       |
| `/flywheel oit exactweather true\|false`                   | 26.2: rain/snow per fragment (`true`) or one cheap weather layer (default). |
| `/flywheel lightSmoothness <mode>`                         | `flat`, `tri_linear`, `smooth` (default) or `smooth_inner_face_corrected`.  |
| `/flywheel limitUpdates on\|off`                           | Update distant instances less often (default on).                           |
| `/flywheel debug info`                                     | Version, backend, settings and instance counts, for bug reports.            |
| `/flywheel debug gpuTimer on\|off\|once\|summary`          | Per-pass GPU timings.                                                       |

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

Java 25 is required. Ordinary Gradle version projects target Minecraft 26.2 and 26.3, with
[Tenon](https://github.com/Warfactory-Official/Tenon) handling compile-time API differences.
The compiler resolves as snapshot `tenon_version` from `https://repo.warfactory.co/snapshots`;
`-Ptenon.source=<checkout>` substitutes a compiler checkout, including the plugin used by `buildSrc`.
Select Minecraft in the Gradle project path:

```powershell
.\gradlew.bat :fabric:26.2:build :neoforge:26.2:build
.\gradlew.bat :fabric:26.3:build :neoforge:26.3:build
```

Root `build` builds every version and loader. Loader jars are written to `build/mods/26.2/` or
`build/mods/26.3/`; intermediate outputs and development run directories remain inside each module's
`versions/<minecraft-version>/` directory.

The matrix is registered by `versions/*/gradle.properties`. Adding a Minecraft target starts with one profile;
settings creates its module nodes automatically, and any necessary source overrides use the existing version
directories. The profile supplies dependency pins and supported mod ranges. Loader plugins, module relationships
and source-layout policy stay in this project.

The reusable `mov.movblock.tenon` Gradle plugin supplies compiler dependencies, javac access options and tracked
preprocessor/macro inputs. This project's convention configures the target's `MC`/loader symbols, relocation map
and owning-layer declaration roots. The same plugin can configure other projects without adopting this matrix.

Development clients use the same project paths:

```powershell
.\gradlew.bat :fabric:26.2:runClient
.\gradlew.bat :neoforge:26.3:runClient
```

Shared sources remain in `<module>/src/<source-set>/` and use native 26.3 imports. Tenon applies the package and type
relocations in `tenon.relocations` to imports before Java resolves them, and masks `#if MC >= 26.3` branches without
changing the files. Compile-time aliases and macros cover changed API shapes used across many files: render-pass
calls, shader sources, image-sampler declarations and uniform storage in `common/src/backendAliases/java`, pose
rotations in `common/src/libAliases/java`. Relocations preserve native type and access checks. Strings, mixin
descriptors and
other changed API shapes use explicit version branches. Package wildcards whose types relocate to different
packages require explicit imports. A substantially different implementation belongs in
`<module>/versions/<minecraft-version>/src/<source-set>/`, at the same relative path as the shared file; it replaces
that file for that version. A mixin config (`*.mixins.json`) there instead lists only that version's mixins, merged
into the shared config. Dependency pins and supported mod ranges live in
`versions/<minecraft-version>/gradle.properties`. No active-source switching or generated production Java is needed.

Java compilation runs without incremental javac: relocations and compile-time declarations can change bindings
without producing an ABI class for Gradle to track. Their files are explicit inputs, so Gradle up-to-date checks and
build caching still work. Source attachments contain Tenon dialect, their relocation map and target Minecraft version
for the IDE plugin; both loaders use Tenon's `sourcesJar` helper for declaration sources and target metadata.
They are not plain Java exports. Compiled API consumers need no Tenon runtime.

### Getting Started (For Developers)

```kotlin
repositories {
    maven("https://repo.warfactory.co/releases")
    // Snapshots (append -SNAPSHOT to the version): maven("https://repo.warfactory.co/snapshots")
}

dependencies {
    // NeoForge
    implementation("dev.engine_room:crankshaft-neoforge:1.6.1+mc26.2")
    // Fabric
    implementation("dev.engine_room:crankshaft-fabric:1.6.1+mc26.2")
}
```

Artifact versions carry their Minecraft version (`+mc26.2`, `+mc26.3`).
Available versions: [Fabric](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-fabric/),
[NeoForge](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-neoforge/).

### License

- `:meshlet`: LGPL-3.0-only
- Everything else: MIT

Third-party code is credited in `THIRD_PARTY_NOTICES`; `:meshlet` and `:iris` carry their own.
