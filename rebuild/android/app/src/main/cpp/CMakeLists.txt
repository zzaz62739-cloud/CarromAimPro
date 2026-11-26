cmake_minimum_required(VERSION 3.22.1)
project(carromphysics LANGUAGES CXX)
add_library(carromphysics SHARED
    PhysicsJni.cpp
    ../../../../native/CarromPhysics.cpp
)
target_include_directories(carromphysics PRIVATE ../../../../native)
target_compile_features(carromphysics PRIVATE cxx_std_17)
target_compile_options(carromphysics PRIVATE -Wall -Wextra -Werror)
