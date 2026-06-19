# Deferred RT Implementation Log

Status: in progress

## Environment

- Workspace: `G:\cpp\radiance`
- Java project: `G:\cpp\radiance\Radiance-custom`
- Native renderer project: `G:\cpp\radiance\MCVR-custom`
- Architecture document: `G:\cpp\radiance\Radiance-custom\docs\deferred-rt-module-architecture.md`
- Visual Studio 2026: `D:\Program Files\Microsoft Visual Studio\18\Community`
- Vulkan SDK: `D:\VulkanSDK\1.4.335.0`
- Python: `C:\ProgramData\miniconda3`
- JDK: `C:\Program Files\Zulu\zulu-21`
- Current date: 2026-06-19
- Shell: PowerShell

## Implementation Requirements

- Work step by step and keep this log updated after each meaningful step.
- Do not rely on temporary shortcuts that make the final architecture harder to reach.
- If a piece cannot be fully implemented in the current phase, record the reason and the intended follow-up here.
- Verify compilation after each step where the codebase is expected to compile.
- Commit code and documentation when a phase milestone is reached.
- Keep the Deferred RT module independent from `RayTracingModule`.
- Preserve downstream contracts for ToneMapping, PostRender, NRD, DLSS, FSR, XeSS and TemporalAccumulation.
- Treat VR array layers and `eyeCount` as first-version requirements.
- Do not expose Blaze3D, Java mixins, Vulkan layouts, or PT SBT concepts in the Deferred RT public contract.

## Current Plan

1. Add a Deferred RT contract shell native module.
2. Add the Java-side module YAML so the existing pipeline can instantiate it manually.
3. Allocate all public output images with `layerCount = eyeCount`.
4. Deterministically clear all outputs in `render3D`.
5. Build native code, then fix integration issues.
6. Commit the first milestone once the contract shell builds.

## Progress

### Step 1: Implementation Log

- Status: completed.
- Notes:
  - `MCVR-custom` already has unrelated modified middleware files. These must not be reverted or included in milestone commits.
  - `Radiance-custom/docs/deferred-rt-module-architecture.md` is currently untracked and was provided as design context.

### Step 2: Native Contract Shell

- Status: completed.
- Files:
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.hpp`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.cpp`
  - `MCVR-custom/src/shader/world/deferred_rt/clear_contract.comp`
  - `MCVR-custom/src/core/render/pipeline.cpp`
- Implemented behavior:
  - Added `DeferredRtModule` as a standalone `WorldModule`.
  - Registered the module in `Pipeline::collectWorldModules()`.
  - Defined zero input images and sixteen public output images.
  - Added a single `PublicOutputContract` table mapping internal neutral names to public legacy exports and formats.
  - `setOrCreateOutputImages()` validates Java/native output formats against that table.
  - Allocates output images with `layerCount = eyeCount`.
  - Creates per-layer views for stereo outputs.
  - Uses a compute clear pass over `image2DArray` outputs, dispatching `z = active eye/layer count`.
  - Keeps all output images in `VK_IMAGE_LAYOUT_GENERAL` after the clear pass for downstream storage/sampled consumers.
- Contract notes:
  - The first fifteen outputs mirror the legacy RT public contract.
  - `gi_hit_distance` is explicit and must be connected to NRD `diffuseHitDepthImage` in future Deferred RT presets.
  - The output contract table is the first `LegacyExportMap` seed. G-buffer and lighting code must write internal graph resources and export through this mapping instead of using `first_hit_*` names internally.
- Deferred work:
  - No scene provider, G-buffer, ray-query lighting, NRD-valid hit distances, deferred shaderpack runtime, or offline runner yet.
  - No Java preset wiring yet; manual pipeline construction should be used for this milestone.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core shaders -- /m`
  - First run reconfigured CMake after glob changes, then linked against the old project graph and failed with unresolved `DeferredRtModule` symbols.
  - Second run succeeded after generated project files included `deferred_rt_module.cpp`.

### Step 3: Java Module YAML

- Status: completed.
- Files:
  - `Radiance-custom/src/main/resources/modules/deferred_rt.yaml`
- Notes:
  - No Java JNI ABI changes were needed.
  - No Java preset changes were made.
- Verification:
  - `gradlew.bat classes` completed successfully using `JAVA_HOME=C:\Program Files\Zulu\zulu-21`.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target INSTALL -- /m` completed successfully.
  - Installed `clear_contract_comp.spv` to:
    - `MCVR-custom/bin/res/world/deferred_rt/clear_contract_comp.spv`
    - `Radiance-custom/src/main/resources/shaders/world/deferred_rt/clear_contract_comp.spv`

### Step 4: Current Manual Test Contract

- Status: documented.
- A manual graph can use:
  - `DeferredRtModule.radiance -> ToneMapping.denoised_radiance`
  - `ToneMapping.mapped_output -> PostRender.ldr_input`
  - `DeferredRtModule.first_hit_depth -> PostRender.first_hit_depth`
  - `DeferredRtModule.radiance -> PostRender.hdr_input`
  - `DeferredRtModule.motion_vector -> PostRender.motion_vector`
  - `DeferredRtModule.normal_roughness -> PostRender.normal_roughness`
  - `PostRender.post_rendered -> final output`
- Expected visual result:
  - Deterministic black output.
  - No real scene visibility or lighting yet.
- Known limitation:
  - Preset mode still uses `RayTracingModule`; Deferred RT must be added manually in pipeline mode for this milestone.
  - Shared shaderpack loading remains tied to `RayTracingModule` for PT/post shaderpack workflows. The contract shell does not depend on it. Deferred shaderpack support must redesign this rather than silently reusing PT stage semantics.

### Step 5: CPU Contract Test Harness

- Status: completed.
- Files:
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_contract.hpp`
  - `MCVR-custom/tests/CMakeLists.txt`
  - `MCVR-custom/tests/deferred_rt_contract_test.cpp`
  - `MCVR-custom/CMakeLists.txt`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.hpp`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.cpp`
- Implemented behavior:
  - Moved Deferred RT public output contract constants into a pure contract header shared by production code and tests.
  - Added layer-count helper functions for mono/stereo output rules.
  - Added `mcvr_tests` executable and CTest registration.
  - Added tests for output count, output order, public/internal names, formats, explicit `gi_hit_distance`, output usage flags and stereo layer helper behavior.
- Design note:
  - The test target intentionally does not link or initialize the Vulkan renderer. This keeps first-milestone contract tests runnable without Minecraft or GPU runtime state, while still testing the same contract constants consumed by `DeferredRtModule`.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target mcvr_tests -- /m` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 1/1 tests passed.

### Step 6: Native Build Compatibility Fix

- Status: completed.
- Files:
  - `MCVR-custom/CMakeLists.txt`
- Issue found:
  - After CMake reconfiguration, full native build re-ran NRD shader generation and failed in the Windows DXBC/FXC backend with `forced to unroll loop, but unrolling failed`.
  - NRD SPIR-V generation completed before the failure, and Radiance's Vulkan NRD wrapper creates pipelines from `pDesc.computeShaderSPIRV`.
  - After disabling DXBC, MSVC then failed compiling NRD `Wrapper.cpp` with `C1060: compiler is out of heap space`, caused by embedding another unused shader container.
  - After disabling both DXBC and DXIL, full native build still failed compiling NRD's SPIR-V-embedded generated headers with `C1060`.
- Implemented behavior:
  - Explicitly set `NRD_EMBEDS_DXBC_SHADERS=OFF` in the project NRD configuration.
  - Explicitly set `NRD_EMBEDS_DXIL_SHADERS=OFF` so the Vulkan build embeds only NRD SPIR-V shaders.
- Design note:
  - This keeps Vulkan NRD SPIR-V support enabled and avoids requiring unused D3D11/D3D12 shader containers to compile on the current toolchain.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target NRD -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core shaders mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - On this Windows toolchain, NRD's generated shader headers should be built with low MSBuild/CL concurrency after a clean CMake regeneration to avoid `C1060`.

### Step 7: First-Milestone Verification

- Status: completed.
- Verification:
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 1/1 tests passed.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target INSTALL -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `gradlew.bat classes` completed successfully with `JAVA_HOME=C:\Program Files\Zulu\zulu-21`.
- Installed artifacts observed:
  - `Radiance-custom/src/main/resources/core.dll`
  - `Radiance-custom/src/main/resources/shaders/world/deferred_rt/clear_contract_comp.spv`
  - `MCVR-custom/bin/core.dll`
  - `MCVR-custom/bin/res/world/deferred_rt/clear_contract_comp.spv`
- Milestone status:
  - Deferred RT contract shell is buildable and has CPU contract coverage.
  - Ready for first milestone commit after staged diff review.

### Step 8: Shared Scene Prepare Extraction

- Status: completed.
- Files:
  - `MCVR-custom/src/core/render/scene_prepare/scene_prepare.hpp`
  - `MCVR-custom/src/core/render/scene_prepare/scene_prepare.cpp`
  - `MCVR-custom/src/core/render/modules/world/ray_tracing/ray_tracing_module.hpp`
  - `MCVR-custom/src/core/render/modules/world/ray_tracing/ray_tracing_module.cpp`
  - Removed old path: `MCVR-custom/src/core/render/modules/world/ray_tracing/submodules/world_prepare.*`
- Implemented behavior:
  - Moved PT-owned `WorldPrepare` into renderer-owned `ScenePrepare`.
  - Removed the back-reference from scene preparation to `RayTracingModule`.
  - Exposed `ScenePrepare::contexts()` through const and non-const accessors so later modules can consume prepared scene contexts without friend/private coupling.
  - Kept RayTracing as the first consumer of the same TLAS/BLAS metadata, buffer-address tables, previous-position tables and hit-group list.
- Design note:
  - This is intentionally a no-rendering-behavior-change extraction. Deferred RT should consume `ScenePrepareContext` next, but the contract shell does not run it until a G-buffer/ray-query pass has a real use for the prepared scene.
  - Existing scene inputs are enough for the next Deferred RT stages: world/entity geometry buffers, material buffer addresses, texture mapping buffer, world/last-world/sky uniforms, TLAS/BLAS metadata and previous-object transforms. Future adapter work is still needed for Blaze3D-style draw packets and raster draw ordering.
- Verification:
  - First `core` build after the file move reconfigured CMake, then MSBuild still tried to compile the removed old path `ray_tracing/submodules/world_prepare.cpp` from the stale project graph.
  - Second `cmake --build MCVR-custom/build-radiance-custom --config Release --target core -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully after regenerated project files took effect.
  - After adding the const context accessor, `cmake --build MCVR-custom/build-radiance-custom --config Release --target core shaders mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 1/1 tests passed.

### Step 9: Scene Packet Metadata Retention

- Status: completed.
- Files:
  - `MCVR-custom/src/core/render/chunks.hpp`
  - `MCVR-custom/src/core/render/chunks.cpp`
  - `MCVR-custom/src/core/render/entities.hpp`
  - `MCVR-custom/src/core/render/entities.cpp`
- Implemented behavior:
  - Preserved per-geometry `geometryType`, `textureId`, vertex count and index count from existing native build tasks.
  - Extended `ChunkRenderData`, `Chunk1` and `Entity` so a neutral scene provider can build real `SceneDrawPacket` ranges and material bindings without scanning vertex data or reaching back into Java.
- Design note:
  - This does not change the JNI input ABI. Java already passes these values; native code previously used part of them for BLAS/material packing and then dropped the metadata needed by raster G-buffer passes.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully after the data-model step.

### Step 10: Scene Provider Boundary

- Status: completed.
- Target files:
  - `MCVR-custom/src/core/render/scene_provider/scene_provider.hpp`
  - `MCVR-custom/src/core/render/scene_provider/mcvr_scene_provider.hpp`
  - `MCVR-custom/src/core/render/scene_provider/mcvr_scene_provider.cpp`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.hpp`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.cpp`
  - `MCVR-custom/tests/deferred_rt_scene_provider_test.cpp`
- Intended behavior:
  - Add a neutral `SceneProvider` contract that exposes views, opaque/cutout/translucent draw packets and acceleration metadata without leaking Java, Blaze3D, Vulkan layout, PT SBT or shaderpack concepts.
  - Add an `McvrSceneProvider` that adapts the current chunk/entity data retained in Step 9.
  - Connect `DeferredRtModule` to begin a scene-provider frame before its current deterministic clear pass, without starting G-buffer rendering yet.
- Design notes:
  - Existing JNI inputs remain sufficient for this phase; the provider consumes native-side scene/geometry/material/texture metadata already supplied by Java.
  - `WORLD_TRANSPARENT` is classified as cutout for first G-buffer bring-up because the current Java input collapses true translucent and alpha-tested block layers into one geometry type.
  - True translucent/water/glass layer identity still requires a later Java/native metadata extension; the provider boundary leaves that extension localized.
- Partial implementation:
  - Added the neutral `SceneProvider` contract and draw/view/range helper declarations.
  - Kept the neutral contract free of `World`, Blaze3D and full Vulkan wrapper includes; concrete source classification lives in an MCVR adapter helper.
  - Added CPU tests for MCVR geometry classification, draw-range accumulation, stereo view/layer mapping and packet stats.
- Verification:
  - First `mcvr_tests` build after adding tests failed because the test target did not include all headers required by `all_extern.hpp`.
  - After reducing the provider header dependencies and fixing test include paths, CMake reconfiguration reached third-party dependency setup and failed updating `zlib-ng` from GitHub: `Failed to connect to github.com port 443`.
  - Reconfigured with Zulu 21 JNI paths and `FETCHCONTENT_UPDATES_DISCONNECTED*` enabled so local third-party dependency state is used without network updates.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 2/2 tests passed.
- Next step:
  - Implement runtime `McvrSceneProvider` packet enumeration and connect `DeferredRtModule` to begin a provider frame before the clear pass.
- Runtime provider implementation:
  - Added `McvrSceneProvider` that reads current chunk/entity render data and emits neutral `SceneDrawPacket`s.
  - Tightened the neutral `SceneProvider` header to depend only on GLM and Vulkan core scalar types; the MCVR adapter keeps the heavier renderer/framework includes.
  - Packet ranges are built from retained per-geometry vertex/index counts rather than scanning vertices.
  - Chunks and entities are bucketed into opaque/cutout/translucent streams with packet/index counters and explicit skipped-packet counters.
  - Views are derived from current and last `WorldUBO`, including per-eye view/projection offsets, layer indices and jitter.
  - Acceleration metadata is exposed from `ScenePrepareContext` without exposing PT SBT or hit-group concepts.
  - `DeferredRtModule` now owns `ScenePrepare`; each `DeferredRtModuleContext` owns an `McvrSceneProvider`.
  - `render3D` runs scene preparation and provider `beginFrame()` before the placeholder clear pass.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully after connecting Deferred RT to `ScenePrepare` and `McvrSceneProvider`.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core shaders mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 2/2 tests passed.
- Deferred work:
  - No G-buffer draw recording yet; the provider now exposes the draw streams required for that next phase.
  - Previous entity object transforms currently mirror current provider transforms unless `ScenePrepareContext` metadata is consumed by a later motion-vector implementation.
  - True translucent/cutout separation still needs a future Java/native metadata extension because current MCVR inputs collapse several render layers into `WORLD_TRANSPARENT`.

## Open Issues

- The first milestone does not implement G-buffer rasterization, ray-query lighting, scene provider extraction, deferred shaderpack runtime, or offline runner.
- Java preset support is intentionally not part of the first manual-test contract shell unless needed for compilation.
- Shader clear defaults are placeholders. They are deterministic, but only valid for contract/resource testing, not rendering quality.
- Manual pipeline outputs must be connected into the final output dependency graph; otherwise Java's topological build will omit unreferenced modules, as designed.

### Step 11: Fixed G-buffer Plan Layer

- Status: completed.
- Target files:
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_gbuffer.hpp`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_gbuffer.cpp`
  - `MCVR-custom/tests/deferred_rt_gbuffer_test.cpp`
  - `MCVR-custom/tests/CMakeLists.txt`
- Intended behavior:
  - Add a CPU-testable plan for the first fixed G-buffer pass before touching Vulkan command recording.
  - Define which Deferred RT public outputs are written by the opaque/cutout G-buffer pass.
  - Define which outputs remain clear-pass placeholders until lighting, reflection, fog, refraction and GI passes are implemented.
  - Keep layer/view rules compatible with the existing stereo array-output contract.
- Notes:
  - This layer is meant to be consumed by the Vulkan pass directly; it should prevent attachment order, output format and stereo layer decisions from becoming implicit in command recording code.
- Implemented behavior:
  - Added a first fixed G-buffer attachment contract for:
    - `primary_albedo_metallic`
    - `primary_specular_albedo`
    - `primary_normal_roughness`
    - `primary_motion_vector`
    - `primary_linear_depth`
    - `primary_depth`
    - `primary_emission`
  - Left radiance, reflection hit distance, direct/indirect/specular lighting, clear layer, fog, refraction and GI hit distance as explicit clear-only placeholders for later passes.
  - Added mono, per-layer stereo and multiview layer planning helpers.
  - Added draw-stream classification so opaque and cutout draw packets participate in the fixed G-buffer, while translucent/additive/overlay packets stay excluded.
  - Cutout is marked as the only first-pass alpha-test draw stream.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 3/3 tests passed.
- Deferred work:
  - No Vulkan render pass, framebuffer, graphics pipeline or shader recording is connected yet; the new plan layer is ready for that implementation.

### Step 12: Fixed Vulkan G-buffer Pass

- Status: in progress.
- Target files:
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.hpp`
  - `MCVR-custom/src/core/render/modules/world/deferred_rt/deferred_rt_module.cpp`
  - `MCVR-custom/src/shader/world/deferred_rt/gbuffer.vert`
  - `MCVR-custom/src/shader/world/deferred_rt/gbuffer.frag`
- Intended behavior:
  - Add a fixed graphics G-buffer pass after the deterministic clear pass.
  - Use `SceneProvider` draw packets only; do not read chunks/entities directly from the module.
  - Write real primary albedo/specular/normal/depth/motion/emission exports for opaque and cutout geometry.
  - Preserve array-layer output compatibility for mono, per-layer stereo fallback and multiview-capable devices.
  - Keep lighting, reflection, fog, refraction and GI outputs as clear-only placeholders until later passes are implemented.
- Notes:
  - The first Vulkan implementation may keep the existing compute clear before G-buffer rendering. That is extra work but behaviorally correct because the render pass overwrites all first-stage G-buffer attachments and leaves later-pass exports deterministic.
- Resume status:
  - Native repository still only has unrelated middleware edits outside Deferred RT ownership.
  - Step 12 code was not yet committed or partially applied; implementation resumes from the clear-only module plus Step 11 CPU G-buffer plan.
- Current substep:
  - Add the native prerequisites needed by indexed raster G-buffer rendering without changing the Java/JNI ABI:
    - depth-image per-layer views must use depth aspect rather than color aspect.
    - packed chunk/entity index buffers must be created with `VK_BUFFER_USAGE_INDEX_BUFFER_BIT` so G-buffer draw calls can bind them directly.
- Implemented prerequisites:
  - `DeviceLocalImage::createPerLayerViews()` now uses the same usage-derived aspect mask as normal image views, so depth images can safely create per-layer 2D views.
  - Chunk packed index buffers now include `VK_BUFFER_USAGE_INDEX_BUFFER_BIT` for both single-build and batched-build paths.
  - Entity packed index buffers now include `VK_BUFFER_USAGE_INDEX_BUFFER_BIT`.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully after the prerequisite changes.
- G-buffer data-path decision:
  - The fixed raster pass will extend the existing Deferred RT descriptor table instead of creating a second pipeline layout. Set 0 remains the compute clear storage-image set.
  - Set 1 will be the bound texture array that mirrors the existing world texture input contract.
  - Set 2 will be a per-frame storage buffer containing provider-derived view/draw records. This avoids Java/JNI changes and avoids oversized push constants.
  - The first fixed pass will use provider view matrices and draw packet material data directly; texture mapping and deeper PBR map interpretation remain a later lighting/material refinement because this milestone only needs primary albedo, normal, depth, motion and emission exports.
- Layer-plan correction:
  - Updated the Step 11 `GBufferLayerPlan` multiview framebuffer rule before creating real framebuffers: framebuffer count stays 1, but framebuffer layer count is 1 for Vulkan multiview render passes. Array-layer selection comes from the subpass view mask, not from framebuffer layers.
  - Updated the CPU G-buffer test expectation accordingly.
- Frame-data layout correction:
  - Split the planned set 2 frame-data binding into two storage buffers:
    - binding 0: `DeferredRtGBufferViewData[]`
    - binding 1: `DeferredRtGBufferDrawData[]`
  - Reason: GLSL cannot naturally expose two unsized runtime arrays from one SSBO. Keeping view and draw records in separate bindings makes the C++/GLSL contract direct and avoids offset-packing shortcuts.
  - No Java/JNI change is required; both buffers are built from the existing native `SceneProvider` view and draw packets.
- Vulkan command path:
  - Added per-frame upload of provider-derived G-buffer view records and draw records.
  - Added storage-buffer descriptor binding for those records in Deferred RT set 2.
  - Added clear-to-G-buffer barriers: public G-buffer attachments transition from compute `GENERAL` clear into `COLOR_ATTACHMENT_OPTIMAL`; clear-only outputs stay in `GENERAL`.
  - Added internal depth transition and fixed G-buffer render pass execution for multiview and per-layer fallback.
  - Added indexed draw recording from `SceneDrawPacket` streams only. Opaque and cutout streams are recorded; cutout enables shader alpha test.
- Shader path:
  - Added fixed `gbuffer.vert` and `gbuffer.frag`.
  - The shader reads provider SSBOs plus existing packed position/material vertex buffers through BDA.
  - The shader writes primary albedo/metallic, specular albedo, normal/roughness, motion vector, linear depth, primary depth and base emission.
  - Motion vectors use the legacy pixel-space convention: previous pixel minus current pixel.
  - Normal/specular texture mapping and full PBR material interpretation remain deferred material-refinement work; the first pass consumes the existing base texture, vertex color, alpha test and provider material flags.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target shaders core -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully after adding the G-buffer pass and shaders.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 3/3 tests passed.
- Current follow-up before milestone commit:
  - Improve provider `previousObjectToWorld` so static geometry motion vectors include camera movement without requiring a Java/JNI input extension.
- Provider motion-vector refinement:
  - `McvrSceneProvider` now derives `previousObjectToWorld` from the last world UBO camera position for chunks and WORLD-space entities.
  - CAMERA and CAMERA_SHIFT entity transforms now use the last world UBO when it is available.
  - This keeps motion-vector inputs native-side and avoids a Java/JNI contract extension for the first G-buffer milestone.
- Texture descriptor safety:
  - Added a native `DeferredRtModule` fallback texture path: a 1x1 white `R8G8B8A8_UNORM` image plus nearest-repeat sampler.
  - The fallback image is uploaded during module build and transitioned to `SHADER_READ_ONLY_OPTIMAL`.
  - Every frame descriptor table now initializes all 4096 texture-array slots to the fallback image before real `Textures::initializeTexture()` / sampler updates overwrite loaded slots.
  - This avoids undefined sampling from unbound combined-image-sampler descriptors while preserving the current Java/JNI texture input contract.
- Multiview fallback correction:
  - Split the G-buffer vertex shader into a per-layer wrapper (`gbuffer.vert`) and a multiview wrapper (`gbuffer_multiview.vert`) over a shared `gbuffer_vertex_common.glsl`.
  - The per-layer shader uses the push-constant `viewIndex`; the multiview shader uses `gl_ViewIndex` and is only attached to the multiview pipeline.
  - Removed the unused multiview extension requirement from the fragment shader.
  - Added `src/shader` to the shader include path and tracked deferred RT `.glsl` includes as shader dependencies.
  - This keeps the non-multiview fallback path buildable and avoids making all G-buffer raster pipelines require multiview support.
- Verification:
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target core -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully after the fallback descriptor initialization change.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target shaders core -- /m:1 /p:CL_MPCount=1 /v:minimal` initially failed after the shader split due to missing include-extension and shader-root include path declarations; both issues were fixed, then the same command completed successfully.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target shaders core mcvr_tests -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully.
  - `ctest --test-dir MCVR-custom/build-radiance-custom -C Release --output-on-failure` completed successfully: 3/3 tests passed.
  - `cmake --build MCVR-custom/build-radiance-custom --config Release --target INSTALL -- /m:1 /p:CL_MPCount=1 /v:minimal` completed successfully and installed `gbuffer_vert.spv`, `gbuffer_multiview_vert.spv` and `gbuffer_frag.spv` to both `MCVR-custom/bin/res/world/deferred_rt` and `Radiance-custom/src/main/resources/shaders/world/deferred_rt`.
