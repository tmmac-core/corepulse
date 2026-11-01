# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md
cmake_minimum_required(VERSION 3.22)
project(coreminer_jni C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# RandomY bringt eigene Architektur-Erkennung mit (x86-64-JIT, ARM64-JIT).
add_subdirectory(third_party/RandomY EXCLUDE_FROM_ALL)

add_library(coreminer_jni SHARED
    src/randomy_jni.cpp
    src/sha3.c)

target_include_directories(coreminer_jni PRIVATE
    third_party/RandomY/src
    src)

# Ausserhalb von Android (JVM-Test auf dem Server) die JNI-Header des JDK nutzen.
if(NOT ANDROID)
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(coreminer_jni PRIVATE ${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2})
endif()

find_package(Threads REQUIRED)
target_link_libraries(coreminer_jni PRIVATE randomx Threads::Threads)

if(ANDROID)
    target_link_libraries(coreminer_jni PRIVATE log)
    # 16-KB-Seiten (Pflicht fuer neue Android-15+-Geraete)
    target_link_options(coreminer_jni PRIVATE "-Wl,-z,max-page-size=16384")
    # Nur Java_*-Funktionen exportieren (vorher ~1.190 interne Symbole). Lokale Symbole bindet der Linker direkt,
    # das vertraegt sich mit dem JIT-Kopiertrick unten.
    target_link_options(coreminer_jni PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
    set_property(TARGET coreminer_jni APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
endif()

# RandomYs ARM64-JIT kopiert seinen Assembler-Block (randomx_program_aarch64 …) in einen eigenen Puffer.
# Ohne -Bsymbolic leitet der ELF-Linker Spruenge auf globale Symbole ueber die PLT
# (z. B. "b.ne randomx_program_aarch64_main_loop@plt"); in der Kopie zeigt der relative Sprung dann
# ins Leere → SIGBUS beim ersten Hash (Android-Emulator 15.10.2026). macOS (Mach-O) bindet direkt.
if(NOT APPLE)
    target_link_options(coreminer_jni PRIVATE "-Wl,-Bsymbolic")
endif()

# Assembler-Teile von RandomY tragen keine GNU-stack-Notiz → Stack explizit nicht ausfuehrbar
if(NOT APPLE)
    target_link_options(coreminer_jni PRIVATE "-Wl,-z,noexecstack")
endif()
