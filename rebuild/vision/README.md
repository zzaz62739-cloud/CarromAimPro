# Screenshot reference fixture (offline only)

Created from the user's portrait Carrom Pool screenshot, 691x1536.

- Board and disc coordinates are approximate hand-measured estimates.
- This is a static offline fixture to validate normalized coordinates and physics.
- It is **not** an image-recognition model and does not read a live game frame.
- Pixel-to-board conversion assumes the estimated board rectangle in the JSON file.
- Do not use this fixture to claim live capture, automatic disc detection, or aiming is complete.

The next legitimate practice-tool step is to implement and test a screenshot importer/board geometry extractor outside a live match, then compare its detections to this fixture.