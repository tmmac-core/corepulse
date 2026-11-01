// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md
// JNI-Bruecke zu RandomY (core-coin/RandomY, BSD-3).
//
// Rechenweg identisch mit coreminer (libethash-cpu/CPUMiner.cpp):
//   seed  = SHA3-512(header[32] || nonce als uint64 Little-Endian)
//   hash  = RandomY(seed) mit festem Cache-Key {53,54,55,56,57}
//   Share, wenn hash als 256-Bit-Big-Endian-Zahl <= boundary
//
// Ein Context haelt Cache/Dataset (einmal pro Prozess, ~256 MiB),
// jeder Mining-Thread bekommt seine eigene VM.

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <new>
#include <thread>
#include <vector>

#include "randomx.h"
#include "sha3.h"

#if defined(__APPLE__) && defined(__aarch64__)
#include <pthread.h>
// macOS: MAP_JIT-Speicher ist pro Thread entweder beschreibbar oder ausfuehrbar. RandomY laesst den Thread
// nach allocMemoryPages im Schreibmodus; zurueck in der JVM ist deren (ebenfalls MAP_JIT) Code-Cache dann
// nicht ausfuehrbar → SIGBUS. Vor jeder Rueckkehr nach Java wieder auf Ausfuehren schalten. Android: no-op.
#define RESTORE_JIT_EXEC() pthread_jit_write_protect_np(1)
#else
#define RESTORE_JIT_EXEC() ((void)0)
#endif

namespace {

const unsigned char kCoreKey[] = {53, 54, 55, 56, 57};

struct Context {
    randomx_flags flags;
    randomx_cache* cache = nullptr;
    randomx_dataset* dataset = nullptr;
    std::atomic<int> jitFallbacks{0};  // VMs, die ohne JIT angelegt werden mussten
};

void releaseContext(Context* ctx) {
    if (ctx == nullptr) return;
    if (ctx->dataset) randomx_release_dataset(ctx->dataset);
    if (ctx->cache) randomx_release_cache(ctx->cache);
    delete ctx;
}

bool lessOrEqualBigEndian(const uint8_t* a, const uint8_t* b) {
    return std::memcmp(a, b, 32) <= 0;
}

void computeHash(randomx_vm* vm, const uint8_t header[32], uint64_t nonce, uint8_t out[32]) {
    uint8_t input[40];
    std::memcpy(input, header, 32);
    for (int i = 0; i < 8; i++) input[32 + i] = (uint8_t)(nonce >> (8 * i));
    uint8_t seed[64];
    sha3_512(input, sizeof input, seed);
    randomx_calculate_hash(vm, seed, sizeof seed, out);
}

}  // namespace

extern "C" {

// Rueckgabe: Context-Handle oder 0. fullMem=false → Light-Modus (nur Cache, langsamer, weniger RAM).
// useJit=false erzwingt den Interpreter (Referenz fuer den Selbsttest, Rueckfall bei JIT-Fehlern).
JNIEXPORT jlong JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeInit(JNIEnv*, jclass, jboolean fullMem, jint initThreads,
                                                                 jboolean useJit) {
  Context* ctx = nullptr;
  try {
    ctx = new Context();
    ctx->flags = randomx_get_flags();
    if (!useJit) ctx->flags = (randomx_flags)(ctx->flags & ~RANDOMX_FLAG_JIT);
#if defined(__APPLE__) && defined(__aarch64__)
    // macOS erlaubt auf MAP_JIT-Speicher kein RWX (CompiledVm::enableAll → SIGBUS in randomx_create_vm).
    // SECURE schaltet pro Programm zwischen Schreiben und Ausfuehren um. Nur Mac-Entwicklerlauf, Android braucht das nicht.
    if (ctx->flags & RANDOMX_FLAG_JIT) ctx->flags = (randomx_flags)(ctx->flags | RANDOMX_FLAG_SECURE);
#endif
    if (fullMem) ctx->flags = (randomx_flags)(ctx->flags | RANDOMX_FLAG_FULL_MEM);

    ctx->cache = randomx_alloc_cache(ctx->flags);
    if (ctx->cache == nullptr) {
        // JIT kann auf manchen Geraeten (W^X-Policy) scheitern → ohne JIT erneut versuchen
        ctx->flags = (randomx_flags)(ctx->flags & ~RANDOMX_FLAG_JIT);
        ctx->cache = randomx_alloc_cache(ctx->flags);
    }
    if (ctx->cache == nullptr) {
        delete ctx;
        RESTORE_JIT_EXEC();
        return 0;
    }
    randomx_init_cache(ctx->cache, kCoreKey, sizeof kCoreKey);

    if (fullMem) {
        ctx->dataset = randomx_alloc_dataset(ctx->flags);
        if (ctx->dataset == nullptr) {
            // Zu wenig Speicher → Light-Modus
            ctx->flags = (randomx_flags)(ctx->flags & ~RANDOMX_FLAG_FULL_MEM);
        } else {
            uint32_t items = randomx_dataset_item_count();
            unsigned n = initThreads > 0 ? (unsigned)initThreads : 1;
            std::vector<std::thread> threads;
            uint32_t per = items / n, start = 0;
            try {
                for (unsigned i = 0; i < n; i++) {
                    uint32_t count = per + (i == n - 1 ? items % n : 0);
                    threads.emplace_back(randomx_init_dataset, ctx->dataset, ctx->cache, start, count);
                    start += count;
                }
            } catch (...) {
                // Gestartete Threads muessen gejoint sein, sonst std::terminate beim Abwickeln
                for (auto& t : threads) t.join();
                throw;
            }
            for (auto& t : threads) t.join();
            // Im Full-Modus braucht die VM den Cache nicht mehr
            randomx_release_cache(ctx->cache);
            ctx->cache = nullptr;
        }
    }
    RESTORE_JIT_EXEC();
    return reinterpret_cast<jlong>(ctx);
  } catch (...) {
    // bad_alloc / Thread-Anlage gescheitert: keine C++-Exception ueber die JNI-Grenze (waere std::terminate)
    releaseContext(ctx);
    RESTORE_JIT_EXEC();
    return 0;
  }
}

JNIEXPORT jint JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeFlags(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<Context*>(handle);
    if (ctx == nullptr) return 0;
    // Musste eine VM ohne JIT laufen, soll das Log nicht "JIT=true" zeigen
    int f = ctx->flags;
    if (ctx->jitFallbacks.load() > 0) f &= ~RANDOMX_FLAG_JIT;
    return (jint) f;
}

JNIEXPORT jlong JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeCreateVm(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<Context*>(handle);
    if (ctx == nullptr) return 0;
    randomx_vm* vm = randomx_create_vm(ctx->flags, ctx->cache, ctx->dataset);
    if (vm == nullptr && (ctx->flags & RANDOMX_FLAG_JIT)) {
        // JIT-Speicher nicht ausfuehrbar (Hersteller-Policy) → Interpreter, gleiche Hashes, nur langsamer
        vm = randomx_create_vm((randomx_flags)(ctx->flags & ~RANDOMX_FLAG_JIT), ctx->cache, ctx->dataset);
        if (vm != nullptr) ctx->jitFallbacks++;
    }
    RESTORE_JIT_EXEC();
    return reinterpret_cast<jlong>(vm);
}

JNIEXPORT void JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeDestroyVm(JNIEnv*, jclass, jlong vm) {
    if (vm) randomx_destroy_vm(reinterpret_cast<randomx_vm*>(vm));
}

JNIEXPORT void JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeRelease(JNIEnv*, jclass, jlong handle) {
    releaseContext(reinterpret_cast<Context*>(handle));
}

// Einzelner Hash (fuer Tests und zur Kontrolle eines Fundes).
JNIEXPORT jbyteArray JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeHash(JNIEnv* env, jclass, jlong vm, jbyteArray header, jlong nonce) {
    uint8_t h[32], out[32];
    if (vm == 0 || env->GetArrayLength(header) != 32) return nullptr;
    env->GetByteArrayRegion(header, 0, 32, reinterpret_cast<jbyte*>(h));
    computeHash(reinterpret_cast<randomx_vm*>(vm), h, (uint64_t)nonce, out);
    jbyteArray result = env->NewByteArray(32);
    env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<jbyte*>(out));
    return result;
}

// Rechnet `count` Nonces ab `startNonce`. Bei einem Treffer: Nonce-Index zurueck,
// Hash in `outHash`. Sonst -1. Kleine Bloecke halten die Reaktion auf neue Jobs schnell.
JNIEXPORT jint JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeSearch(JNIEnv* env, jclass, jlong vm, jbyteArray header,
                                                                   jlong startNonce, jint count, jbyteArray boundary,
                                                                   jbyteArray outHash) {
    uint8_t h[32], b[32], out[32];
    if (vm == 0 || env->GetArrayLength(header) != 32 || env->GetArrayLength(boundary) != 32 ||
        env->GetArrayLength(outHash) != 32) {
        return -1;
    }
    env->GetByteArrayRegion(header, 0, 32, reinterpret_cast<jbyte*>(h));
    env->GetByteArrayRegion(boundary, 0, 32, reinterpret_cast<jbyte*>(b));
    auto* v = reinterpret_cast<randomx_vm*>(vm);
    for (jint i = 0; i < count; i++) {
        computeHash(v, h, (uint64_t)startNonce + (uint64_t)i, out);
        if (lessOrEqualBigEndian(out, b)) {
            env->SetByteArrayRegion(outHash, 0, 32, reinterpret_cast<jbyte*>(out));
            return i;
        }
    }
    return -1;
}

JNIEXPORT jbyteArray JNICALL
Java_com_tellmemoreaboutcore_miner_core_NativeRandomY_nativeSha3_1512(JNIEnv* env, jclass, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> in(len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(in.data()));
    uint8_t out[64];
    sha3_512(in.data(), in.size(), out);
    jbyteArray result = env->NewByteArray(64);
    env->SetByteArrayRegion(result, 0, 64, reinterpret_cast<jbyte*>(out));
    return result;
}

}  // extern "C"
