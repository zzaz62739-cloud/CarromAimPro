# Screenshot reference fixture and multi-mode vision requirements

The first user-provided screenshot (portrait, 691x1536) is one **sample layout only**.

- Its board bounds and disc centers are approximate, hand-measured estimates.
- The JSON fixture is for offline coordinate and physics checks; it is not a detector.
- Other Carrom Pool modes may have different disc counts, colors, formations, and striker positions.
- Runtime recognition must detect the board and enumerate the discs present in each frame. It must not hard-code this sample's 14-disc arrangement or assume a fixed formation.
- The vision pipeline must support variable counts, reject uncertain detections, and pass only positively detected discs to physics. The physics core currently accepts 1–32 discs; exceeding that bound must fail safely and be handled explicitly.
- Validate recognition against multiple distinct mode screenshots before claiming multi-mode support.

Do not claim live capture recognition or automatic aiming is complete based on this fixture.