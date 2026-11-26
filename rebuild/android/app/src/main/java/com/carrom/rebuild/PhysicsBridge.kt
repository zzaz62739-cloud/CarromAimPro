package com.carrom.rebuild

/**
 * JNI bridge to the separately tested C++ simulation engine.
 * Input disc layout: id,x,y,vx,vy,radius,mass,active (8 floats each).
 * Output layout: x,y,vx,vy,active (5 floats each).
 */
object PhysicsBridge {
    init { System.loadLibrary("carromphysics") }

    external fun simulateNative(
        discs: FloatArray,
        board: FloatArray,
        maxSeconds: Float,
        stepSeconds: Float
    ): FloatArray?
}
