<div style="text-align: center;">
<h1>CrankShaft</h1>
<h6>Unofficial Minecraft 26.2 and 26.3 Vulkan + OpenGL port of Flywheel.</h6>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Warfactory-Official/CrankShaft?style=flat&color=900c3f" alt="License"></a>
<br>
</div>

### About

CrankShaft is an **unofficial port** of [Flywheel](https://github.com/Engine-Room/Flywheel) to Minecraft 26.2 and 26.3
for NeoForge and Fabric, building on the work of Jozufozu and the Engine-Room team and on CrankShaft's 1.12.2
backport. Its shaderpack support adapts [Colorwheel](https://github.com/djefrey/Colorwheel)'s shaderpack contract and
wavelet OIT. It is not affiliated with or supported by Flywheel, Colorwheel or their maintainers; please report bugs
here.

The bundled **Vanillate** (our counterpart to Vanillin) instances vanilla entities and block entities. Turn it or
single entries off on its settings page, in `config/vanillate.json` (Fabric) or in `config/vanillate-client.toml`
(NeoForge).

`multiversion` is the development branch for both Minecraft versions; `26.2` keeps the earlier single-version build.

### Features

- OpenGL and Vulkan backends with GPU culling and indirect draws, selected automatically.
- Order-independent transparency (OIT) for translucent instances and terrain. On 26.3, vanilla's Improved
  Transparency uses it on both graphics APIs.
- With Sodium: GPU-culled opaque terrain and mesh-shader backends.
- Iris shaderpacks draw instances and terrain through the pack's own programs, shadows included, with deferred
  multi-layer transparency for Sundial, iterationRP and SEUS PTGI.
- Instance motion vectors for Super Resolution's velocity extension.

### Requirements

- Minecraft 26.2 or 26.3, NeoForge or Fabric (+ Fabric API), Java 25.
- Any GPU and driver that run the matching vanilla version; features the hardware lacks fall back.

Optional, with the versions CrankShaft is built and tested against:

| Mod                                                      | 26.2        | 26.3                                                        |
|----------------------------------------------------------|-------------|-------------------------------------------------------------|
| [Sodium](https://modrinth.com/mod/sodium)                | 0.9.2       | 0.9.3-beta.1                                                |
| [Iris](https://modrinth.com/mod/iris)                    | 1.11.4      | 1.11.7 (OpenGL); experimental 1.12.0 (OpenGL and Vulkan)    |
| [Voxy](https://modrinth.com/mod/voxy)                    | 0.2.19-beta | 0.2.20-beta                                                 |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | 3.3.3   | 3.3.4                                                       |

Sodium enables the `opaque` and `full` terrain modes, the mesh-shader backends and the settings pages.

### Backends

`/flywheel backend` shows or sets the backend. By default CrankShaft picks the best supported one; an unsupported
backend falls back to the next, down to `off`, and logs why.

| Backend (`flywheel:`)              | API    | Notes                                                          |
|------------------------------------|--------|----------------------------------------------------------------|
| `vk_indirect`                      | Vulkan | Default on Vulkan.                                             |
| `vk_mesh_shader`                   | Vulkan | `VK_EXT_mesh_shader`; requires Sodium.                         |
| `indirect`                         | OpenGL | Default on OpenGL, except Intel.                               |
| `gl_mesh_shader`                   | OpenGL | `GL_NV_mesh_shader` (NVIDIA); requires Sodium.                 |
| `instancing`                       | OpenGL | Default on Intel and the OpenGL fallback; no GPU culling.      |
| `iris_indirect`, `iris_instancing` | OpenGL | Used automatically while a shaderpack is active.               |
| `iris_vk_indirect`                 | Vulkan | Shaderpacks with experimental Iris on 26.3.                    |
| `iris_mesh_shader`                 | OpenGL | Experimental shaderpack mesh terrain (NVIDIA); never automatic. |
| `off`                              | —      | Vanilla draws everything.                                      |

`-Dcrankshaft.shader.strict=true` makes recoverable availability failures fatal, which helps bug reports.

**Vulkan:** select *Options → Video Settings → Graphics API → Vulkan* and restart; CrankShaft follows. On NeoForge,
also set `earlyWindowControl = false` in `config/fml.toml`. Shaderpacks on Vulkan need experimental Iris.

### Shaderpacks

While a shaderpack is active CrankShaft uses its `iris_` backends; nothing needs configuring. Packs implementing
Colorwheel's contract use it directly. Bundled recipes add transparency and fixes for the packs below; they match the
pack's shader sources rather than its file name, so other versions run without them. Other packs draw instances
through their standard programs, without OIT. Any terrain mode but `off` also draws chunk terrain through the pack.

| Shaderpack                         | Tested versions                                  | Transparency        |
|------------------------------------|--------------------------------------------------|---------------------|
| Complementary Reimagined / Unbound | r5.9.3                                           | Wavelet             |
| Complementary Euphoria             | r5.8.1, r5.9.3 + Patches 1.10.5                  | Wavelet             |
| Solas                              | V3.7b                                            | Wavelet             |
| BSL                                | v10.1.5, v10.1.8                                 | Wavelet             |
| Insanity Shader Universal          | v1.650                                           | Wavelet             |
| MakeUp UltraFast                   | 9.5e, 9.5f                                       | Wavelet             |
| Photon                             | v1.3b                                            | Wavelet             |
| Bliss (Chocapic13 edit)            | v2.1.2                                           | Wavelet             |
| Sildur's Vibrant Shaders Extreme   | v2.01, v2.02                                     | Wavelet             |
| Hysteria                           | v1.2.1, v1.2.2                                   | Wavelet             |
| Rethinking Voxels                  | r0.1-beta9                                       | Wavelet             |
| Mellow                             | v3.4.1a                                          | Wavelet             |
| AstraLex                           | V93.0                                            | Wavelet             |
| Sundial                            | Alpha Build 2026-08-28, 2026-09-25, 2026-10-30   | Deferred or Forward |
| iterationRP                        | Alpha 0.8.28, 0.8.29, 0.8.31                     | Deferred or Forward |
| SEUS PTGI                          | HRR Test 2.1, HRR 3, HRR Test 2.1 GFME 1.22 RC1 | Deferred or Forward |

**Shaderpack Transparency** chooses between the last two modes. **Deferred** (default) shades every layer behind the
nearest transparent surface in the pack's own translucent pass, with its lighting, refraction and reflection, in exact
depth order. **Forward** lights those layers approximately and blends them with wavelets, at a lower GPU cost.

### Commands

Client-side; changes are saved to the config. Tab completion lists every option, including debugging subcommands.

| Command                                                    | Effect                                                      |
|------------------------------------------------------------|-------------------------------------------------------------|
| `/flywheel backend [<id>\|DEFAULT]`                        | Show or set the backend.                                    |
| `/flywheel terrain off\|translucent\|opaque\|full`         | Terrain mode (below).                                       |
| `/flywheel oit mode auto\|wavelet\|kbuffer\|mlab\|abuffer` | OIT method (below); shaderpacks use their own.              |
| `/flywheel oit layers <0-32>`, `/flywheel oit reset`       | Layer budget of the active insert method (`0` = preset).    |
| `/flywheel oit exactweather true\|false`                   | 26.2: rain and snow per fragment instead of one layer.      |
| `/flywheel lightSmoothness <mode>`                         | `flat`, `tri_linear`, `smooth` (default) or `smooth_inner_face_corrected`. |
| `/flywheel limitUpdates on\|off`                           | Update distant instances less often (default on).           |
| `/flywheel debug info`                                     | Version, backend, settings and instance counts.             |
| `/flywheel debug gpuTimer on\|off\|once\|summary`          | Per-pass GPU timings.                                       |

| OIT method       | Behaviour                                                                    |
|------------------|------------------------------------------------------------------------------|
| `auto` (default) | `mlab` with fragment-shader interlock, else `wavelet`.                       |
| `wavelet`        | Approximate wavelet transmittance; works everywhere.                         |
| `kbuffer`        | First *K* fragments per pixel (preset 4), sorted.                            |
| `mlab`           | *K* sorted layers (preset 8), merging the farthest; needs interlock.         |
| `abuffer`        | Per-pixel lists, nearest *N* (preset 16); most memory.                       |

The insert methods are exact within their budget. On OpenGL they need a GPU that runs `indirect` and exposes 28
storage-buffer bindings; otherwise they use `wavelet`.

| Terrain mode            | Opaque terrain         | Translucent terrain      | Requires                   |
|-------------------------|------------------------|--------------------------|----------------------------|
| `off`                   | Vanilla / Sodium       | Vanilla / Sodium         | —                          |
| `translucent` (default) | Vanilla / Sodium       | CrankShaft OIT           | —                          |
| `opaque`                | CrankShaft, GPU-culled | Sodium                   | Sodium, GPU-driven backend |
| `full`                  | CrankShaft, GPU-culled | CrankShaft OIT           | Sodium, GPU-driven backend |

GPU-driven backends are `indirect`, `gl_mesh_shader`, `vk_indirect` and `vk_mesh_shader`; on others `opaque` acts as
`off` and `full` as `translucent`.

### Configuration

With Sodium, settings are on CrankShaft's pages in Sodium's video settings. They are stored in
`config/crankshaft.json` (Fabric) or `config/crankshaft-client.toml` (NeoForge). `concurrentExtraction` (default on)
extracts entity and block entity render states on worker threads; turn it off if a mod hooking entity rendering
misbehaves. On OpenGL, shader sources and programs are cached per driver under `cache/crankshaft/`, which is safe
to delete.

### Building

Java 25 is required. [Tenon](https://github.com/Warfactory-Official/Tenon) compiles the shared sources for each
Minecraft version (snapshot `tenon_version` from `https://repo.warfactory.co/snapshots`; `-Ptenon.source=<checkout>`
uses a local checkout).

```powershell
.\gradlew.bat :fabric:26.2:build :neoforge:26.2:build
.\gradlew.bat :fabric:26.3:build :neoforge:26.3:build
.\gradlew.bat :neoforge:26.3:runClient
```

Jars are written to `build/mods/<minecraft-version>/`. Shared sources in `<module>/src/` use 26.3 imports, which
`tenon.relocations` maps for 26.2, with `#if MC >= 26.3` for remaining differences. A file under
`<module>/versions/<minecraft-version>/src/` replaces its shared counterpart for that version. Dependency pins and
supported mod ranges live in `versions/<minecraft-version>/gradle.properties`.

### Using CrankShaft as a dependency

```kotlin
repositories {
    maven("https://repo.warfactory.co/releases")
}

dependencies {
    implementation("dev.engine_room:crankshaft-neoforge:1.7.1+mc26.2") // NeoForge
    implementation("dev.engine_room:crankshaft-fabric:1.7.1+mc26.2")   // Fabric
}
```

Versions carry their Minecraft version (`+mc26.2`, `+mc26.3`); see the
[Fabric](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-fabric/) and
[NeoForge](https://repo.warfactory.co/releases/dev/engine_room/crankshaft-neoforge/) listings.

### License

- `:meshlet`: LGPL-3.0-only
- Everything else: MIT

Third-party code is credited in `THIRD_PARTY_NOTICES`; `:meshlet` and `:iris` carry their own.
