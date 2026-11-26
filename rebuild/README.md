# Kala-reference rebuild (independent implementation)

This directory is the clean implementation track. The legacy CarromAimPro app remains untouched; this branch isolates the replacement work.

## Evidence from the supplied KalaEngine 2.1.0 APK

Inspected locally from the APK already uploaded in the conversation:

- The ARM64 `libblackbox.so` dynamically depends on `libandroid.so`, `libEGL.so`, `libGLESv3.so`, and `libjnigraphics.so`.
- Its undefined imports include `eglSwapBuffers`, `eglGetCurrentContext`, `eglGetCurrentDisplay`, `eglGetCurrentSurface`, `eglQuerySurface`, and a broad set of GLES drawing calls.
- Strings include `Dear ImGui 1.92.8`, `imgui_impl_opengl3`, and `AIMAI_ENGINE`.
- The same library imports `process_vm_readv`, `dladdr`, `dl_iterate_phdr`, `mmap`, and pthread/signal functions. These are clues about native process/memory and hook infrastructure, not proof of exactly how it attaches to the game.
- The APK is protected/obfuscated; the DEX is not meaningfully readable through a basic strings pass. The visible update/passcode workflow has not yet been identified. The Jiagu `.appkey` belongs to the protection layer and must not be mistaken for the app's update code.

## Engineering rules

1. Do not reuse the previous floating-window/CaptureService implementation.
2. Never install a global touch interceptor or consume game touch events.
3. No drawing until the target game package is positively identified and a valid game frame is available.
4. Keep rendering, image analysis, and physics on separate components/threads with bounded work and explicit shutdown.
5. First implement and test geometry/physics independently. Rendering integration is a later decision after manifest/runtime evidence is understood.
6. Do not copy protected KalaEngine binaries, bypass its license/update checks, or claim to have extracted its hidden algorithm. Reimplement observable behavior independently.
7. Do not publish an APK as game-tested until it has been tested on a real device in the actual game. Automated build success is not device validation.

## Current milestone

A standalone C++ normalized-coordinate physics core exists in `native/`. It handles pair collisions, rail restitution, exponential velocity damping, corner-pocket removal, trace sampling, and a bounded simulation duration. The smoke test compiles with C++17 and passes locally.

The constants are provisional defaults, not values extracted from KalaEngine. They must be calibrated against real Carrom Pool observations before being described as matching the reference app.
