# Third-party notices

## Create

This is an independent addon for [Create](https://github.com/Creators-of-Create/Create).
Create is required at runtime and is not bundled in this repository.

The Interface Module models adapt the mounting base of Create's Stock Link.
The motion sensor models adapt Create's kinetic gauge housing. Their original
texture atlases are referenced by resource identifier and are supplied by the
installed Create mod. Those original and adapted elements are not claimed as
original Retro Computers artwork and are not relicensed by this project.

Create's code is MIT-licensed; its assets are All Rights Reserved by The Create
Team / The Creators of Create. The corresponding notice is preserved in
[`src/main/resources/META-INF/licenses/create-models.txt`](src/main/resources/META-INF/licenses/create-models.txt).
See [Create's license](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/LICENSE.md)
and the team's [discussion of asset use in community addons](https://github.com/Creators-of-Create/Create/issues/9857).

## JetBrains Mono

The terminal font is JetBrains Mono, copyright 2020 The JetBrains Mono Project
Authors, distributed under the SIL Open Font License 1.1. The font and its
license are included in `src/main/resources/assets/retrocomputers/font/`.

## NeoForge MDK

This repository starts from the NeoForge ModDevGradle MDK for Minecraft 1.21.1.
Its original template notice is retained in `TEMPLATE_LICENSE.txt`.

## Runtime dependencies and bundled languages

GraalPy and its dependencies are resolved by Gradle. Full builds also package
locally installed Linux toolchains: OpenJDK, Rust, .NET/Roslyn, GCC, binutils,
Lua, Python, bubblewrap, FUSE and their runtime libraries. These components
retain their respective licenses; the packaging scripts include available
upstream license notices. The original C quota helper belongs to this project.

Compiled runtime archives and tools are build outputs, not tracked source files.
Distributing a generated binary requires complying with the licenses of every
component included in that binary, including applicable source requirements.
