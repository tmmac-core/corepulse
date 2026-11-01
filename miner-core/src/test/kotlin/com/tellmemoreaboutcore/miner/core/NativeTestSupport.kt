// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner.core

/** Host-Lib (build/host) geladen? Sonst werden native Tests uebersprungen. */
internal val nativeLibLoadable: Boolean by lazy { runCatching { NativeRandomY.nativeFlags(0) }.isSuccess }
