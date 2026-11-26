#include "../native/CarromPhysics.h"
#include <cassert>

int main() {
    // Head-on collision: striker transfers forward momentum to the target.
    carrom::Board board;
    board.pocketRadius = 0.045;
    board.frictionPerSecond = 1.2;
    carrom::Disc striker{1, {0.25, 0.5}, {0.8, 0.0}, 0.015, 1.0, true};
    carrom::Disc target{2, {0.55, 0.5}, {0.0, 0.0}, 0.015, 1.0, true};
    auto collision = carrom::simulate({striker, target}, board, 5.0, 1.0 / 240.0);
    assert(collision.finalDiscs.size() == 2);
    assert(!collision.trace.empty());
    assert(collision.finalDiscs[0].position.x > 0.25);
    assert(collision.finalDiscs[1].position.x > 0.55);
    assert(collision.settled);

    // Rail collision: a disc must reverse its horizontal direction.
    carrom::Board railBoard;
    railBoard.pocketRadius = 0.001;
    railBoard.frictionPerSecond = 0.0;
    carrom::Disc railDisc{3, {0.5, 0.5}, {3.0, 0.0}, 0.015, 1.0, true};
    auto rail = carrom::simulate({railDisc}, railBoard, 0.5, 1.0 / 240.0);
    assert(rail.finalDiscs[0].velocity.x < 0.0);

    // Corner pocket: entering the pocket removes the disc from play.
    carrom::Disc pocketDisc{4, {0.06, 0.06}, {-1.0, -1.0}, 0.015, 1.0, true};
    auto pocket = carrom::simulate({pocketDisc}, board, 1.0, 1.0 / 240.0);
    assert(!pocket.finalDiscs[0].active);

    // Invalid time step must return safely without simulating.
    auto invalid = carrom::simulate({striker}, board, 1.0, 0.0);
    assert(invalid.finalDiscs.size() == 1);
    assert(invalid.trace.empty());
}
