cmake_minimum_required(VERSION 3.10.2)
project("gamepadd" C)

# The daemon is a standalone PIE executable (named "gamepadd", no .so suffix,
# so AGP does not stage it into jniLibs). The Gradle build copies the built
# executable into the APK's assets (see copyGamepadDaemon in build.gradle.kts);
# the app extracts it to filesDir and launches it via su.
add_executable(gamepadd gamepadd.c)
target_compile_options(gamepadd PRIVATE -std=c11 -Wall -Wextra -O2)
target_link_libraries(gamepadd log)
