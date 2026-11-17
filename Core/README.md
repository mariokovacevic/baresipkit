# Core

The C core both SDKs link: the BaresipKit facade over baresip and libre, with its C ABI in
`include/baresip_kit.h`, built from pinned sources with the same patches for every platform.

| Path | Content |
|---|---|
| `include/`, `src/` | the facade: `baresip_kit.h` and its implementation |
| `third_party/` | sources built into the core, with their licenses |
| `Build/` | the build script, the pinned versions (`versions.env`), the module sets and the CMake helpers; it writes to `Build/out` and clones into `Build/.deps` |
| `Patches/` | the patches applied to the pinned baresip and libre sources, each with its rationale in [Patches/README.md](Patches/README.md) |
| `Tests/` | the C tests and their scripted peers, `Fuzz/` and `Integration/` (Asterisk and coturn in Docker) |

| Guide | |
|---|---|
| [Docs/Core/Building.md](../Docs/Core/Building.md) | building the core, the core tests, the integration suite and fuzzing |
| [Docs/Core/C-ABI.md](../Docs/Core/C-ABI.md) | the conventions of the C ABI |
| [Docs/Core/CompatibilityMatrix.md](../Docs/Core/CompatibilityMatrix.md) | each requirement and how baresip covers it |
