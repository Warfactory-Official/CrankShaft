<div align="center">
<h1>CrankShaft</h1>
<h6>Unofficial 1.12.2 backport of Flywheel.</h6>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Warfactory-Official/CrankShaft?style=flat&color=900c3f" alt="License"></a>
<br>
</div>

### About

CrankShaft is an **unofficial 1.12.2 backport** of
[Flywheel](https://github.com/Engine-Room/Flywheel) by Jozufozu and the
Engine-Room team. It is not affiliated with, endorsed by, or supported by
Flywheel or its maintainers. Bugs in CrankShaft are CrankShaft's, not
Flywheel's — please file them here.

Shipped alongside CrankShaft is **Vanillate**, the 1.12.2 counterpart to
Vanillin: instanced rendering for entities and block entities via
Flywheel.

### Requirements

- [Cleanroom](https://github.com/CleanroomMC/Cleanroom) loader (vanilla Forge
  1.12.2 is not supported) and JDK 25+

### Instancing

Flywheel provides an alternate, unified path for entity and block entity
rendering that takes advantage of GPU instancing. Flywheel gives the developer
the flexibility to define their instance formats and write custom shaders to
ingest that data.

To accommodate the developer and leave more in the hands of the engine,
Flywheel provides a custom shader loading and templating system to hide the
details of the CPU/GPU interface.

### Mod Compatibility

None of the mods below are required. The lists describe how each interacts 
with CrankShaft when both are installed. Any mod not listed here can be
assumed compatible — please file an issue if you find otherwise.

#### Patched

CrankShaft has explicit compat code for these.

- *Renderers:* Sodium ports (Neonium, Vintagium, Relictium, Celeritas),
  Nothirium (with RenderLib).
- *Lighting engine:* Alfheim, Cubic Chunks.
- *Smart animated textures:* LoliASM (aka CensoredASM).
- *Dynamic lights:* Celeritas Dynamic Lights, OptiFine's Dynamic Lights.
- *Uniforms:* AquaAcrobatics (swimming), Wings (flying), Fluidlogged-API 
  (fluidlogged blocks) are recognized by CrankShaft's uniform writers.
- *Vanillate:* Future-MC's backported Bell uses the vanilla bell visualizer.

> [!NOTE]
> CrankShaft fixes Celeritas Dynamic Lights' entity lighting on Neonium, Vintagium, and
> Relictium.

#### Partially compatible

Some of their features do not extend to Flywheel-instanced visuals.

- *OptiFine* — shaderpacks are not supported.

#### Incompatible

Both mods will load and run, but their interaction is broken on Flywheel-instanced
visuals.

- *Albedo* (elucent) and *ColoredLux* (zeith / dragon-forge) — they bind
  their own GL programs for the world and entity render passes.

### Getting Started (For Developers)

Add the following repo and dependency to your `build.gradle`:

```groovy
repositories {
    maven {
        name "warfactory releases"
        url "https://repo.warfactory.co/releases"
    }
}

dependencies {
    implementation "dev.engine_room:crankshaft:${crankshaft_version}"
    // Optional: vanilla visualizers (chest, item, item frame, …)
    implementation "dev.engine_room:vanillate:${vanillate_version}"
}
```

`${crankshaft_version}` and `${vanillate_version}` get replaced by the versions
you want to use, eg. `1.0.0`. The two artifacts are versioned independently.

For a list of available CrankShaft versions, you can
check [the maven](https://repo.warfactory.co/releases/dev/engine_room/crankshaft/).

Snapshot builds are published to `https://repo.warfactory.co/snapshots` with
versions like `1.0.0-SNAPSHOT`.
