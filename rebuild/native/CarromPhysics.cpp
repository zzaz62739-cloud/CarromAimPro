#include "CarromPhysics.h"
#include <algorithm>
#include <cmath>
#include <utility>

namespace carrom {
namespace {
Vec2 add(Vec2 a, Vec2 b) { return {a.x+b.x, a.y+b.y}; }
Vec2 sub(Vec2 a, Vec2 b) { return {a.x-b.x, a.y-b.y}; }
Vec2 mul(Vec2 a, double s) { return {a.x*s, a.y*s}; }
double dot(Vec2 a, Vec2 b) { return a.x*b.x + a.y*b.y; }
double length(Vec2 a) { return std::sqrt(dot(a,a)); }
void resolvePair(Disc& a, Disc& b, double restitution) {
    Vec2 delta = sub(b.position, a.position);
    double distance = length(delta), minDistance = a.radius + b.radius;
    if (distance >= minDistance) return;
    Vec2 n = distance > 1e-9 ? mul(delta, 1.0/distance) : Vec2{1.0,0.0};
    double invA = 1.0/std::max(a.mass,1e-9), invB = 1.0/std::max(b.mass,1e-9);
    double overlap = minDistance - distance;
    a.position = sub(a.position, mul(n, overlap * invA/(invA+invB)));
    b.position = add(b.position, mul(n, overlap * invB/(invA+invB)));
    double along = dot(sub(b.velocity, a.velocity),n);
    if (along >= 0.0) return;
    double impulse = -(1.0+restitution)*along/(invA+invB);
    a.velocity = sub(a.velocity, mul(n, impulse*invA));
    b.velocity = add(b.velocity, mul(n, impulse*invB));
}
bool inPocket(const Disc& d, const Board& b) {
    const Vec2 pockets[] = {{b.left,b.top},{b.right,b.top},{b.left,b.bottom},{b.right,b.bottom}};
    for (const auto& p : pockets) if (length(sub(d.position,p)) < b.pocketRadius) return true;
    return false;
}
void resolveRails(Disc& d, const Board& b) {
    const double l=b.left+d.radius, r=b.right-d.radius;
    const double t=b.top+d.radius, bot=b.bottom-d.radius;
    if (d.position.x < l) { d.position.x=l; if(d.velocity.x<0) d.velocity.x=-d.velocity.x*b.restitution; }
    if (d.position.x > r) { d.position.x=r; if(d.velocity.x>0) d.velocity.x=-d.velocity.x*b.restitution; }
    if (d.position.y < t) { d.position.y=t; if(d.velocity.y<0) d.velocity.y=-d.velocity.y*b.restitution; }
    if (d.position.y > bot) { d.position.y=bot; if(d.velocity.y>0) d.velocity.y=-d.velocity.y*b.restitution; }
}
}
SimulationResult simulate(std::vector<Disc> discs, const Board& board, double maxSeconds, double dt) {
    SimulationResult out;
    if (!(dt>0.0) || !(maxSeconds>0.0) || board.right<=board.left || board.bottom<=board.top) {
        out.finalDiscs=std::move(discs); return out;
    }
    const int steps=static_cast<int>(std::ceil(maxSeconds/dt));
    for(int frame=0; frame<steps; ++frame) {
        bool moving=false;
        for(auto& d:discs) if(d.active) {
            d.position=add(d.position,mul(d.velocity,dt));
            resolveRails(d,board);
        }
        for(size_t i=0;i<discs.size();++i) if(discs[i].active)
            for(size_t j=i+1;j<discs.size();++j) if(discs[j].active)
                resolvePair(discs[i],discs[j],board.restitution);
        const double damping=std::exp(-std::max(0.0,board.frictionPerSecond)*dt);
        for(auto& d:discs) if(d.active) {
            if(inPocket(d,board)) { d.active=false; d.velocity={0,0}; continue; }
            d.velocity=mul(d.velocity,damping);
            if(length(d.velocity)<board.stopSpeed) d.velocity={0,0};
            if(length(d.velocity)>0) moving=true;
            if(frame%4==0) out.trace.push_back({(frame+1)*dt,d.id,d.position});
        }
        out.duration=(frame+1)*dt;
        if(!moving) { out.settled=true; break; }
    }
    out.finalDiscs=std::move(discs);
    return out;
}
}
