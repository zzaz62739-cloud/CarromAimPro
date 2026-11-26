#include <jni.h>
#include <vector>
#include <cstdint>
#include "CarromPhysics.h"

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_carrom_rebuild_PhysicsBridge_simulateNative(
        JNIEnv* env, jobject,
        jfloatArray inputDiscs, jfloatArray inputBoard,
        jfloat maxSeconds, jfloat stepSeconds) {
    if (inputDiscs == nullptr || inputBoard == nullptr) return nullptr;
    const jsize discValues = env->GetArrayLength(inputDiscs);
    const jsize boardValues = env->GetArrayLength(inputBoard);
    if (discValues <= 0 || discValues % 8 != 0 || boardValues != 8) return nullptr;

    std::vector<jfloat> raw(static_cast<size_t>(discValues));
    jfloat boardRaw[8]{};
    env->GetFloatArrayRegion(inputDiscs, 0, discValues, raw.data());
    env->GetFloatArrayRegion(inputBoard, 0, 8, boardRaw);
    if (env->ExceptionCheck()) return nullptr;

    std::vector<carrom::Disc> discs;
    discs.reserve(static_cast<size_t>(discValues / 8));
    for (jsize i = 0; i < discValues; i += 8) {
        carrom::Disc d;
        d.id = static_cast<int>(raw[static_cast<size_t>(i)]);
        d.position = {raw[static_cast<size_t>(i+1)], raw[static_cast<size_t>(i+2)]};
        d.velocity = {raw[static_cast<size_t>(i+3)], raw[static_cast<size_t>(i+4)]};
        d.radius = raw[static_cast<size_t>(i+5)];
        d.mass = raw[static_cast<size_t>(i+6)];
        d.active = raw[static_cast<size_t>(i+7)] > 0.5f;
        discs.push_back(d);
    }
    carrom::Board board;
    board.left=boardRaw[0]; board.top=boardRaw[1];
    board.right=boardRaw[2]; board.bottom=boardRaw[3];
    board.pocketRadius=boardRaw[4]; board.restitution=boardRaw[5];
    board.frictionPerSecond=boardRaw[6]; board.stopSpeed=boardRaw[7];

    const auto result = carrom::simulate(std::move(discs), board, maxSeconds, stepSeconds);
    std::vector<jfloat> output;
    output.reserve(result.finalDiscs.size() * 5);
    for (const auto& d : result.finalDiscs) {
        output.push_back(static_cast<jfloat>(d.position.x));
        output.push_back(static_cast<jfloat>(d.position.y));
        output.push_back(static_cast<jfloat>(d.velocity.x));
        output.push_back(static_cast<jfloat>(d.velocity.y));
        output.push_back(d.active ? 1.0f : 0.0f);
    }
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (array != nullptr && !output.empty())
        env->SetFloatArrayRegion(array, 0, static_cast<jsize>(output.size()), output.data());
    return array;
}
