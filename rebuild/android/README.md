# Fresh Android capture base

This is a separate Android app module. It does not reuse the previous overlay or capture service.

Current milestone:
- Requests Android's official MediaProjection consent for each capture session.
- Captures screen frames on a dedicated HandlerThread.
- Closes every acquired Image promptly and keeps no full-resolution bitmap copies.
- Uses a foreground media-projection service with explicit stop and teardown.
- Does not request overlay permission, draw circles/lines, intercept touches, or claim to detect Carrom Pool yet.

Next milestones:
1. Verify capture lifecycle and device compatibility.
2. Implement positive Carrom Pool board recognition with confidence gates.
3. Connect normalized detections to the standalone physics engine.
4. Only then design a non-touchable rendering layer and test its impact on game touch latency.

## Native physics integration
The Android module now loads the C++ physics core through JNI. The native library is built from the same `rebuild/native/CarromPhysics.cpp` used by the standalone smoke tests. This milestone only links the engine; screen recognition and aim-to-velocity calibration are not yet implemented.
