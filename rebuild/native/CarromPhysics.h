#pragma once
#include <cstdint>
#include <vector>

namespace carrom {
struct Vec2 { double x{}, y{}; };
struct Disc {
    int id{};
    Vec2 position{};
    Vec2 velocity{};
    double radius{0.012};
    double mass{1.0};
    bool active{true};
};
struct Board {
    double left{0.0}, top{0.0}, right{1.0}, bottom{1.0};
    double pocketRadius{0.045};
    double restitution{0.92};
    double frictionPerSecond{0.22};
    double stopSpeed{0.003};
};
struct TracePoint { double time{}; int discId{}; Vec2 position{}; };
struct SimulationResult {
    std::vector<Disc> finalDiscs;
    std::vector<TracePoint> trace;
    double duration{};
    bool settled{};
};

// Coordinates are normalized to the playable board rectangle.
SimulationResult simulate(std::vector<Disc> discs, const Board& board,
                          double maxSeconds = 12.0, double stepSeconds = 1.0 / 240.0);
}
