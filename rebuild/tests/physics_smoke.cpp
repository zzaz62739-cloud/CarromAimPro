#include "../native/CarromPhysics.h"
#include <cassert>
#include <cmath>
#include <limits>

int main() {
    carrom::Board board;
    board.pocketRadius = 0.045;
    board.frictionPerSecond = 1.2;

    // Head-on collision transfers forward momentum.
    carrom::Disc striker{1, {0.25, 0.5}, {0.8, 0.0}, 0.015, 1.0, true};
    carrom::Disc target{2, {0.55, 0.5}, {0.0, 0.0}, 0.015, 1.0, true};
    auto collision = carrom::simulate({striker, target}, board, 5.0, 1.0/240.0);
    assert(collision.finalDiscs.size() == 2 && !collision.trace.empty());
    assert(collision.finalDiscs[0].position.x > 0.25);
    assert(collision.finalDiscs[1].position.x > 0.55 && collision.settled);

    // Glancing collision transfers momentum along the contact normal.
    carrom::Board glancingBoard;
    glancingBoard.pocketRadius = 0.001;
    glancingBoard.frictionPerSecond = 0.5;
    carrom::Disc moving{5, {0.25, 0.45}, {1.0, 0.0}, 0.02, 1.0, true};
    carrom::Disc offset{6, {0.55, 0.485}, {0.0, 0.0}, 0.02, 1.0, true};
    auto glancing = carrom::simulate({moving, offset}, glancingBoard, 1.0, 1.0/480.0);
    assert(glancing.finalDiscs[1].velocity.y > 0.0);
    assert(glancing.finalDiscs[1].velocity.x > 0.0);

    // Rail collision reverses horizontal direction.
    carrom::Board railBoard;
    railBoard.pocketRadius = 0.001;
    railBoard.frictionPerSecond = 0.0;
    carrom::Disc railDisc{3, {0.5, 0.5}, {3.0, 0.0}, 0.015, 1.0, true};
    auto rail = carrom::simulate({railDisc}, railBoard, 0.5, 1.0/240.0);
    assert(rail.finalDiscs[0].velocity.x < 0.0);

    // Corner pocket removes the disc before the straight rail clamps it.
    carrom::Disc pocketDisc{4, {0.06, 0.06}, {-1.0, -1.0}, 0.015, 1.0, true};
    auto pocket = carrom::simulate({pocketDisc}, board, 1.0, 1.0/240.0);
    assert(!pocket.finalDiscs[0].active);

    // Invalid numerical inputs fail closed and return the original discs.
    auto zeroDt = carrom::simulate({striker}, board, 1.0, 0.0);
    auto hugeDt = carrom::simulate({striker}, board, 1.0, 0.1);
    auto nanVelocityDisc = striker;
    nanVelocityDisc.velocity.x = std::numeric_limits<double>::quiet_NaN();
    auto nanVelocity = carrom::simulate({nanVelocityDisc}, board, 1.0, 1.0/240.0);
    auto invalidMassDisc = striker;
    invalidMassDisc.mass = 0.0;
    auto invalidMass = carrom::simulate({invalidMassDisc}, board, 1.0, 1.0/240.0);
    assert(zeroDt.trace.empty() && hugeDt.trace.empty());
    assert(nanVelocity.trace.empty() && invalidMass.trace.empty());
    assert(zeroDt.finalDiscs.size() == 1 && invalidMass.finalDiscs.size() == 1);
    // Screenshot-derived, manually estimated fixture: striker moving upward into a
    // central cluster should produce at least one target-disc motion.
    carrom::Board snapshotBoard;
    snapshotBoard.left = 0.04; snapshotBoard.top = 0.05;
    snapshotBoard.right = 0.96; snapshotBoard.bottom = 0.95;
    snapshotBoard.pocketRadius = 0.035;
    snapshotBoard.frictionPerSecond = 0.35;
    carrom::Disc sampleStriker{20, {0.505, 0.837}, {0.0, -1.5}, 0.025, 1.0, true};
    carrom::Disc sampleTarget{21, {0.520, 0.400}, {0.0, 0.0}, 0.025, 1.0, true};
    auto snapshotShot = carrom::simulate({sampleStriker, sampleTarget}, snapshotBoard, 3.0, 1.0/240.0);
    assert(snapshotShot.finalDiscs.size() == 2);
    assert(snapshotShot.finalDiscs[1].position.y < 0.400 ||
           std::abs(snapshotShot.finalDiscs[1].velocity.y) > 0.01);

    // Different game modes/layouts may contain different numbers of discs.
    // The physics API must preserve every detected disc without assuming a
    // fixed cluster shape or a fixed count (up to its documented 32-disc cap).
    for (const int count : {4, 12, 24, 32}) {
        std::vector<carrom::Disc> layout;
        for (int i = 0; i < count; ++i) {
            const int col = i % 8;
            const int row = i / 8;
            layout.push_back(carrom::Disc{
                100 + i,
                {0.15 + 0.10 * col, 0.20 + 0.18 * row},
                {0.0, 0.0}, 0.012, 1.0, true
            });
        }
        auto variableLayout = carrom::simulate(layout, board, 0.1, 1.0/240.0);
        assert(variableLayout.finalDiscs.size() == static_cast<size_t>(count));
    }

}
