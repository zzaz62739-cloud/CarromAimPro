#include "../native/CarromPhysics.h"
#include <cassert>
int main() {
    carrom::Board b;
    b.pocketRadius=0.02;
    b.frictionPerSecond=1.2;
    carrom::Disc striker{1,{0.25,0.5},{0.8,0.0},0.015,1.0,true};
    carrom::Disc target{2,{0.55,0.5},{0.0,0.0},0.015,1.0,true};
    auto result=carrom::simulate({striker,target},b,5.0,1.0/240.0);
    assert(result.finalDiscs.size()==2);
    assert(!result.trace.empty());
    assert(result.finalDiscs[0].position.x > 0.25);
    assert(result.finalDiscs[1].position.x > 0.55);
    assert(result.settled);
}
