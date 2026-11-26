package com.neurallite.app.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareScannerTierTest {

    private val gb = HardwareScanner.GB

    @Test
    fun `tier 1 requires at least 8GB and 8 cores`() {
        assertEquals(1, HardwareScanner.computeTier(8 * gb, 8))
        assertEquals(1, HardwareScanner.computeTier(12 * gb, 12))
        assertEquals(2, HardwareScanner.computeTier(8 * gb - 1, 8))
        assertEquals(2, HardwareScanner.computeTier(8 * gb, 7))
    }

    @Test
    fun `tier 2 requires at least 4GB and 6 cores`() {
        assertEquals(2, HardwareScanner.computeTier(4 * gb, 6))
        assertEquals(2, HardwareScanner.computeTier(7 * gb, 6))
        assertEquals(3, HardwareScanner.computeTier(4 * gb - 1, 6))
        assertEquals(3, HardwareScanner.computeTier(4 * gb, 5))
    }

    @Test
    fun `tier 3 requires at least 3GB and 4 cores`() {
        assertEquals(3, HardwareScanner.computeTier(3 * gb, 4))
        assertEquals(3, HardwareScanner.computeTier(3 * gb + 512, 5))
        assertEquals(4, HardwareScanner.computeTier(3 * gb - 1, 4))
        assertEquals(4, HardwareScanner.computeTier(3 * gb, 3))
    }

    @Test
    fun `tier 4 covers minimal and boundary inputs`() {
        assertEquals(4, HardwareScanner.computeTier(0L, 0))
        assertEquals(4, HardwareScanner.computeTier(2 * gb, 8))
        assertEquals(4, HardwareScanner.computeTier(16 * gb, 2))
    }

    @Test
    fun `hardware profile json round trip preserves all fields`() {
        val original = HardwareScanner.HardwareProfile(
            totalRamBytes = 8 * gb,
            availRamBytes = 5 * gb,
            cpuCores = 8,
            cpuAbi = "arm64-v8a",
            gpuRenderer = "Adreno (TM) 740",
            freeStorageBytes = 64 * gb,
            apiLevel = 34,
            tier = 1
        )
        val restored = HardwareScanner.HardwareProfile.fromJson(original.toJson())
        assertEquals(original, restored)
        assertEquals("Flagship", restored.tierLabel)
    }

    @Test
    fun `tier labels map cleanly`() {
        fun profileWithTier(t: Int) = HardwareScanner.HardwareProfile(
            totalRamBytes = gb,
            availRamBytes = gb,
            cpuCores = 4,
            cpuAbi = "arm64-v8a",
            gpuRenderer = "Unknown GPU",
            freeStorageBytes = gb,
            apiLevel = 26,
            tier = t
        )
        assertEquals("Flagship", profileWithTier(1).tierLabel)
        assertEquals("Mid-range", profileWithTier(2).tierLabel)
        assertEquals("Entry", profileWithTier(3).tierLabel)
        assertEquals("Minimal", profileWithTier(4).tierLabel)
    }
}
