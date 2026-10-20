package com.agentdesk.core.device

import com.agentdesk.core.common.model.DeviceTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Empirical stress test harness for live hardware telemetry, battery fluctuations,
 * charging toggles, thermal events, and reactive ResourceGovernor throttling.
 */
class TelemetryGovernorStressTest {

    private class StressableDeviceProfiler : DeviceProfiler() {
        var simLowMemory: Boolean = false
        var simAvailableStorageMb: Long = 10_000L
        var simTotalStorageMb: Long = 64_000L
        var simAvailableRamMb: Long = 4_096L
        var simTotalRamMb: Long = 8_192L
        var simCpuCores: Int = 8
        var simTier: DeviceTier = DeviceTier.FULL

        val thermalFlow = MutableStateFlow(0)
        val batteryFlow = MutableStateFlow(BatteryState(percent = 100, isCharging = false))

        fun setBattery(percent: Int, isCharging: Boolean) {
            batteryFlow.value = BatteryState(percent = percent, isCharging = isCharging)
        }

        fun setThermal(status: Int) {
            thermalFlow.value = status
        }

        override fun isLowMemory(): Boolean = simLowMemory
        override fun getAvailableStorageMb(): Long = simAvailableStorageMb
        override fun getTotalStorageMb(): Long = simTotalStorageMb
        override fun getAvailableRamMb(): Long = simAvailableRamMb
        override fun getTotalRamMb(): Long = simTotalRamMb
        override fun getCpuCores(): Int = simCpuCores
        override fun classifyTier(): DeviceTier = simTier
        override fun getBatteryPercent(): Int = batteryFlow.value.percent
        override fun isCharging(): Boolean = batteryFlow.value.isCharging
        override fun isBatteryLow(): Boolean = batteryFlow.value.percent < 20 && !batteryFlow.value.isCharging
        override fun getThermalStatus(): Int = thermalFlow.value
        override fun observeThermalStatus(): Flow<Int> = thermalFlow
        override fun observeBatteryState(): Flow<BatteryState> = batteryFlow
    }

    private lateinit var profiler: StressableDeviceProfiler
    private lateinit var governor: ResourceGovernor

    @Before
    fun setUp() {
        profiler = StressableDeviceProfiler()
        governor = ResourceGovernor(profiler)
    }

    @Test
    fun `stress rapid battery drop 25 to 20 to 19 to 15 triggers immediate throttle`() = runBlocking {
        // 1. Initial healthy state at 100%
        assertTrue("100% battery must allow indexing", governor.canIndex())
        assertTrue("100% battery must allow LLM", governor.canRunLlm())
        assertTrue("Reactive observeCanIndex must emit true", governor.observeCanIndex().first())
        assertTrue("Reactive observeCanRunLlm must emit true", governor.observeCanRunLlm().first())

        // 2. Drop to 25% discharging
        profiler.setBattery(25, isCharging = false)
        assertTrue("25% battery must allow indexing", governor.canIndex())
        assertTrue("25% battery must allow LLM", governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())
        assertNull(governor.currentConstraintReason())

        // 3. Drop to 20% discharging (exact boundary threshold)
        profiler.setBattery(20, isCharging = false)
        assertTrue("20% boundary must still allow indexing", governor.canIndex())
        assertTrue("20% boundary must still allow LLM", governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())
        assertNull(governor.currentConstraintReason())

        // 4. Drop to 19% discharging (< 20% threshold: immediate throttle)
        profiler.setBattery(19, isCharging = false)
        assertFalse("19% discharging must pause indexing", governor.canIndex())
        assertFalse("19% discharging must throttle LLM", governor.canRunLlm())
        assertFalse("Reactive observeCanIndex must emit false at 19%", governor.observeCanIndex().first())
        assertFalse("Reactive observeCanRunLlm must emit false at 19%", governor.observeCanRunLlm().first())
        assertEquals("Battery level is below 20%.", governor.currentConstraintReason())

        // 5. Drop further to 15% discharging
        profiler.setBattery(15, isCharging = false)
        assertFalse("15% discharging must pause indexing", governor.canIndex())
        assertFalse("15% discharging must throttle LLM", governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())
        assertEquals("Battery level is below 20%.", governor.currentConstraintReason())

        // 6. Deep drop to 5% discharging
        profiler.setBattery(5, isCharging = false)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())

        // 7. Critical 0% discharging
        profiler.setBattery(0, isCharging = false)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())
    }

    @Test
    fun `stress charging toggles unthrottles critical battery instantly`() = runBlocking {
        // Battery at 15% discharging: throttled
        profiler.setBattery(15, isCharging = false)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())

        // Plug into charger at 15%: immediately unthrottled
        profiler.setBattery(15, isCharging = true)
        assertTrue("Charging at 15% must unthrottle indexing", governor.canIndex())
        assertTrue("Charging at 15% must unthrottle LLM", governor.canRunLlm())
        assertTrue("Reactive observeCanIndex must emit true when charging", governor.observeCanIndex().first())
        assertTrue("Reactive observeCanRunLlm must emit true when charging", governor.observeCanRunLlm().first())
        assertNull(governor.currentConstraintReason())

        // Unplug charger while still at 15%: immediately throttled again
        profiler.setBattery(15, isCharging = false)
        assertFalse("Unplugging at 15% must re-throttle indexing", governor.canIndex())
        assertFalse("Unplugging at 15% must re-throttle LLM", governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())

        // Even at 0% battery, connecting charger allows execution
        profiler.setBattery(0, isCharging = true)
        assertTrue("0% on charger must allow indexing", governor.canIndex())
        assertTrue("0% on charger must allow LLM", governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())

        // Unplugging at 0% throttles
        profiler.setBattery(0, isCharging = false)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())

        // Charging recovers back to 20% and unplugged: remains unthrottled
        profiler.setBattery(20, isCharging = false)
        assertTrue(governor.canIndex())
        assertTrue(governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())
    }

    @Test
    fun `stress thermal transitions cycle through all levels with recovery`() = runBlocking {
        profiler.setBattery(80, isCharging = false)

        val allowedThermals = listOf(0, 1, 2)
        for (temp in allowedThermals) {
            profiler.setThermal(temp)
            assertTrue("Thermal $temp must permit indexing", governor.canIndex())
            assertTrue("Thermal $temp must permit LLM", governor.canRunLlm())
            assertTrue(governor.observeCanIndex().first())
            assertTrue(governor.observeCanRunLlm().first())
            assertNull(governor.currentConstraintReason())
        }

        val throttledThermals = listOf(3, 4, 5, 6)
        for (temp in throttledThermals) {
            profiler.setThermal(temp)
            assertFalse("Thermal $temp must pause indexing", governor.canIndex())
            assertFalse("Thermal $temp must throttle LLM", governor.canRunLlm())
            assertFalse("Reactive observeCanIndex must emit false at thermal $temp", governor.observeCanIndex().first())
            assertFalse("Reactive observeCanRunLlm must emit false at thermal $temp", governor.observeCanRunLlm().first())
            assertEquals("Device is too hot. Pausing heavy operations.", governor.currentConstraintReason())
        }

        // Cool-down recovery from severe/critical heat back to moderate (status 2)
        profiler.setThermal(2)
        assertTrue("Cooling to thermal 2 must recover indexing", governor.canIndex())
        assertTrue("Cooling to thermal 2 must recover LLM", governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())
        assertNull(governor.currentConstraintReason())
    }

    @Test
    fun `stress thermal overrides charging state so hot charging device remains throttled`() = runBlocking {
        // Device is at 10% battery, but PLUGGED IN
        profiler.setBattery(10, isCharging = true)
        assertTrue(governor.canIndex())
        assertTrue(governor.canRunLlm())

        // Thermal spikes to SEVERE (3) while charging
        profiler.setThermal(3)
        assertFalse("Charging must not bypass severe thermal throttle for indexing", governor.canIndex())
        assertFalse("Charging must not bypass severe thermal throttle for LLM", governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())
        assertEquals("Device is too hot. Pausing heavy operations.", governor.currentConstraintReason())

        // Cools down to LIGHT (1) while still charging
        profiler.setThermal(1)
        assertTrue(governor.canIndex())
        assertTrue(governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())
    }

    @Test
    fun `stress high frequency boundary oscillation preserves consistency over 100 cycles`() = runBlocking {
        for (cycle in 1..100) {
            // Drop to 19%
            profiler.setBattery(19, isCharging = false)
            assertFalse("Cycle $cycle: 19% must throttle canIndex", governor.canIndex())
            assertFalse("Cycle $cycle: 19% must throttle canRunLlm", governor.canRunLlm())
            assertFalse("Cycle $cycle: 19% must emit false for observeCanIndex", governor.observeCanIndex().first())
            assertFalse("Cycle $cycle: 19% must emit false for observeCanRunLlm", governor.observeCanRunLlm().first())

            // Rise to 20%
            profiler.setBattery(20, isCharging = false)
            assertTrue("Cycle $cycle: 20% must permit canIndex", governor.canIndex())
            assertTrue("Cycle $cycle: 20% must permit canRunLlm", governor.canRunLlm())
            assertTrue("Cycle $cycle: 20% must emit true for observeCanIndex", governor.observeCanIndex().first())
            assertTrue("Cycle $cycle: 20% must emit true for observeCanRunLlm", governor.observeCanRunLlm().first())
        }
    }

    @Test
    fun `stress high frequency charging jitter preserves consistency over 100 cycles`() = runBlocking {
        profiler.setBattery(15, isCharging = false)

        for (cycle in 1..100) {
            // Charging connected
            profiler.setBattery(15, isCharging = true)
            assertTrue("Cycle $cycle: charging must permit canIndex", governor.canIndex())
            assertTrue("Cycle $cycle: charging must permit canRunLlm", governor.canRunLlm())
            assertTrue("Cycle $cycle: charging must emit true for observeCanIndex", governor.observeCanIndex().first())
            assertTrue("Cycle $cycle: charging must emit true for observeCanRunLlm", governor.observeCanRunLlm().first())

            // Charging disconnected
            profiler.setBattery(15, isCharging = false)
            assertFalse("Cycle $cycle: discharging must throttle canIndex", governor.canIndex())
            assertFalse("Cycle $cycle: discharging must throttle canRunLlm", governor.canRunLlm())
            assertFalse("Cycle $cycle: discharging must emit false for observeCanIndex", governor.observeCanIndex().first())
            assertFalse("Cycle $cycle: discharging must emit false for observeCanRunLlm", governor.observeCanRunLlm().first())
        }
    }

    @Test
    fun `stress reactive flow collector accurately observes rapid battery transition stream`() = runBlocking {
        val indexEmissions = mutableListOf<Boolean>()
        val llmEmissions = mutableListOf<Boolean>()

        val jobIndex = launch(Dispatchers.Unconfined) {
            governor.observeCanIndex().collect { indexEmissions.add(it) }
        }
        val jobLlm = launch(Dispatchers.Unconfined) {
            governor.observeCanRunLlm().collect { llmEmissions.add(it) }
        }

        // Sequence of rapid battery changes
        // Initial state is 100% discharging -> true
        profiler.setBattery(25, isCharging = false) // true
        profiler.setBattery(20, isCharging = false) // true
        profiler.setBattery(19, isCharging = false) // false
        profiler.setBattery(15, isCharging = false) // false
        profiler.setBattery(15, isCharging = true)  // true
        profiler.setBattery(10, isCharging = true)  // true
        profiler.setBattery(10, isCharging = false) // false
        profiler.setBattery(20, isCharging = false) // true

        jobIndex.cancel()
        jobLlm.cancel()

        val expected = listOf(true, true, true, false, false, true, true, false, true)
        assertEquals("observeCanIndex must emit expected transition sequence", expected, indexEmissions)
        assertEquals("observeCanRunLlm must emit expected transition sequence", expected, llmEmissions)
    }

    @Test
    fun `stress concurrent telemetry thrashing maintains stability and settles correctly`() = runBlocking {
        val running = AtomicBoolean(true)
        val readCount = AtomicInteger(0)
        val exceptions = AtomicInteger(0)
        val latch = CountDownLatch(4)

        // Thread 1: Rapid random battery emissions
        val batteryThread = Thread {
            val random = Random(42)
            while (running.get()) {
                val pct = random.nextInt(0, 101)
                val chg = random.nextBoolean()
                profiler.setBattery(pct, chg)
                Thread.yield()
            }
            latch.countDown()
        }

        // Thread 2: Rapid random thermal emissions
        val thermalThread = Thread {
            val random = Random(1337)
            while (running.get()) {
                val th = random.nextInt(0, 7)
                profiler.setThermal(th)
                Thread.yield()
            }
            latch.countDown()
        }

        // Thread 3: Reader 1 querying synchronous governor methods
        val readerThread1 = Thread {
            while (running.get()) {
                try {
                    governor.canIndex()
                    governor.canRunLlm()
                    governor.canUseRichUi()
                    governor.currentConstraintReason()
                    readCount.incrementAndGet()
                } catch (_: Exception) {
                    exceptions.incrementAndGet()
                }
            }
            latch.countDown()
        }

        // Thread 4: Reader 2 querying reactive flows
        val readerThread2 = Thread {
            while (running.get()) {
                try {
                    runBlocking {
                        governor.observeCanIndex().first()
                        governor.observeCanRunLlm().first()
                    }
                    readCount.incrementAndGet()
                } catch (_: Exception) {
                    exceptions.incrementAndGet()
                }
            }
            latch.countDown()
        }

        batteryThread.start()
        thermalThread.start()
        readerThread1.start()
        readerThread2.start()

        Thread.sleep(300)
        running.set(false)
        latch.await(2, TimeUnit.SECONDS)

        assertEquals("Zero exceptions allowed under concurrent thrashing", 0, exceptions.get())
        assertTrue("High throughput reads completed", readCount.get() > 200)

        // Verify post-thrashing clean settle
        // 1. Settle to 15% discharging, thermal 0 -> must be throttled
        profiler.setBattery(15, isCharging = false)
        profiler.setThermal(0)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())

        // 2. Settle to 15% charging, thermal 0 -> must unthrottle
        profiler.setBattery(15, isCharging = true)
        assertTrue(governor.canIndex())
        assertTrue(governor.canRunLlm())
        assertTrue(governor.observeCanIndex().first())
        assertTrue(governor.observeCanRunLlm().first())

        // 3. Settle to thermal 4 (critical) while charging -> must throttle due to thermal
        profiler.setThermal(4)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertFalse(governor.observeCanIndex().first())
        assertFalse(governor.observeCanRunLlm().first())
    }

    @Test
    fun `stress cascading constraints hierarchy and recovery order`() = runBlocking {
        // Healthy baseline
        assertTrue(governor.canIndex())
        assertTrue(governor.canRunLlm())
        assertNull(governor.currentConstraintReason())

        // 1. Storage drops below 500MB
        profiler.simAvailableStorageMb = 450L
        assertFalse("Storage < 500MB pauses indexing", governor.canIndex())
        assertTrue("Storage 450MB on BALANCED/FULL still allows LLM", governor.canRunLlm())
        assertEquals("Available storage is below 500MB.", governor.currentConstraintReason())

        // 2. RAM drops below 1024MB
        profiler.simAvailableRamMb = 800L
        assertFalse(governor.canIndex())
        assertFalse("RAM < 1024MB throttles LLM", governor.canRunLlm())

        // 3. Thermal spikes to 4
        profiler.setThermal(4)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertEquals("Available storage is below 500MB.", governor.currentConstraintReason())

        // 4. Recover storage and RAM, leaving only thermal
        profiler.simAvailableStorageMb = 10_000L
        profiler.simAvailableRamMb = 4_096L
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertEquals("Device is too hot. Pausing heavy operations.", governor.currentConstraintReason())

        // 5. Recover thermal, drop battery to 14%
        profiler.setThermal(1)
        profiler.setBattery(14, isCharging = false)
        assertFalse(governor.canIndex())
        assertFalse(governor.canRunLlm())
        assertEquals("Battery level is below 20%.", governor.currentConstraintReason())

        // 6. Connect charger: full recovery
        profiler.setBattery(14, isCharging = true)
        assertTrue(governor.canIndex())
        assertTrue(governor.canRunLlm())
        assertNull(governor.currentConstraintReason())
    }
}
