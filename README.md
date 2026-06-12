<div align="center">
<h1>CrankShaft</h1>
<h6>Unofficial Minecraft 26.2 port of Flywheel.</h6>
<a href="LICENSE"><img src="https://img.shields.io/github/license/Warfactory-Official/CrankShaft?style=flat&color=900c3f" alt="License"></a>
<br>
</div>

### About

CrankShaft is an **unofficial port** of [Flywheel](https://github.com/Engine-Room/Flywheel)
to Minecraft 26.2, carrying forward the work of Jozufozu and the Engine-Room team as well as
CrankShaft's own 1.12.2 backport. It runs on NeoForge and Fabric. It is not affiliated with,
endorsed by, or supported by Flywheel or its maintainers. Bugs in CrankShaft are
CrankShaft's, not Flywheel's — please file them here.

Shipped alongside CrankShaft is **Vanillate**, the 26.2 counterpart to Vanillin: instanced
rendering for vanilla entities and block entities via Flywheel. It is bundled inside the CrankShaft jar
and is **off by default** — until you opt in, it registers nothing and vanilla renders as usual.
Turn it on with `"enabled": true` in `config/vanillin.json` (Fabric) or `enabled = true` in
`config/vanillin-client.toml` (NeoForge). Individual entities and block entities can then be
enabled or disabled per entry in the same file.

### Requirements

- Minecraft 26.2 with NeoForge or Fabric, and JDK 25+.

### Instancing

Flywheel provides an alternate, unified path for entity and block entity
rendering that takes advantage of GPU instancing. Flywheel gives the developer
the flexibility to define their instance formats and write custom shaders to
ingest that data.

To accommodate the developer and leave more in the hands of the engine,
Flywheel provides a custom shader loading and templating system to hide the
details of the CPU/GPU interface.

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
    implementation("dev.engine_room:crankshaft:1.0.0+mc26.2:neoforge")
    // Fabric
    implementation("dev.engine_room:crankshaft:1.0.0+mc26.2:fabric")
}
```

For a list of available CrankShaft versions, you can
check [the maven](https://repo.warfactory.co/releases/dev/engine_room/crankshaft/).

If you want the bleeding edge:
```kotlin
repositories {
    maven("https://repo.warfactory.co/snapshots")
}

implementation("dev.engine_room:crankshaft:1.0.0+mc26.2-SNAPSHOT:fabric")
```

### License

- `:meshlet`: LGPL-3.0-only
- Everything else: MIT

Third-party code incorporated elsewhere in the tree is credited in `THIRD_PARTY_NOTICES`.
