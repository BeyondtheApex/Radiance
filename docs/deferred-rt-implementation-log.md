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

## Open Issues

- The first milestone does not implement G-buffer rasterization, ray-query lighting, scene provider extraction, deferred shaderpack runtime, or offline runner.
- Java preset support is intentionally not part of the first manual-test contract shell unless needed for compilation.
- Shader clear defaults are placeholders. They are deterministic, but only valid for contract/resource testing, not rendering quality.
- Manual pipeline outputs must be connected into the final output dependency graph; otherwise Java's topological build will omit unreferenced modules, as designed.
