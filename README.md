# Infinite

## What is Infinite?

Infinite is a client-side Level of Detail (LoD) mod for Minecraft 26.3 running on the Vulkan backend alongside Sodium.

Unlike traditional LoD mods that rely solely on the hardware graphics pipeline for tiny distant quads, Infinite uses a GPU compute software rasterizer for small opaque LoD quads into a visibility buffer and resolves them in a fullscreen pass (handing large or close quads to a hardware pass). This greatly improves performance when compared to other LoD mods.

Does not yet support shaders. Shader support will be implemented when Aperture (the successor to Iris) is released.

**DISCLAIMER:** AI (specifically Claude Opus) was used as an assistant during the development of this mod. While all core ideas and design, including pipeline implementations are my own, a significant portion of the backend code was AI-generated, and almost all code was AI reviewed to catch bugs and maintain consistent formatting.

## Credits

Infinite bundles LWJGL Zstd and LZ4 modules, including native libraries, for compressed LoD storage:

| Library | License | Notes |
| --- | --- | --- |
| [LWJGL](https://www.lwjgl.org/) | [BSD 3-Clause](https://www.lwjgl.org/license) | Zstd and LZ4 bindings plus natives, bundled in `META-INF/jars` |
| [Zstandard](https://github.com/facebook/zstd) | [BSD 3-Clause](https://github.com/facebook/zstd/blob/dev/LICENSE) | Native library inside the bundled LWJGL Zstd natives |
| [LZ4](https://github.com/lz4/lz4) | [BSD 2-Clause](https://github.com/lz4/lz4/blob/dev/lib/LICENSE) | Native library inside the bundled LWJGL LZ4 natives |
