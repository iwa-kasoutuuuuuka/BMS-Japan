package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackTest {

    private fun unit(
        voltage: Double, current: Double, soc: Double, remaining: Double,
        state: ChargeState = ChargeState.CHARGING, power: Double = 0.5,
        maxCell: Double = 3.35, minCell: Double = 3.30, maxT: Int = 30, minT: Int = 25,
        alarms: List<Alarm> = emptyList(),
    ) = LiveData(
        cellVoltages = emptyList(), temperatures = emptyList(),
        totalVoltage = voltage, current = current, soc = soc,
        maxCellVoltage = maxCell, minCellVoltage = minCell,
        maxTemperature = maxT, minTemperature = minT,
        state = state, remainingCapacity = remaining,
        cellCount = 16, temperatureSensorCount = 2, cycleCount = 10,
        balancing = false, chargeMos = true, dischargeMos = true,
        averageCellVoltage = 3.32, cellVoltageDelta = 0.05, powerKw = power,
        alarms = alarms,
    )

    private val a = unit(53.0, -10.0, 80.0, 200.0, maxCell = 3.40, minT = 20)
    private val b = unit(52.0, -12.0, 60.0, 150.0, minCell = 3.25, maxT = 35, alarms = listOf(Alarm.CELL_OV1))

    @Test fun parallel() {
        val p = PackSummary.of(PackMode.PARALLEL, listOf(a, b))!!
        assertEquals(52.5, p.voltage, 1e-9)       // 平均
        assertEquals(22.0, p.current, 1e-9)       // 合計（大きさ）
        assertEquals(350.0, p.remainingAh, 1e-9)  // 合計
        assertEquals(70.0, p.soc, 1e-9)           // 平均
        assertEquals(1.0, p.powerKw, 1e-9)
        assertEquals(2, p.unitCount)
    }

    @Test fun series() {
        val p = PackSummary.of(PackMode.SERIES, listOf(a, b))!!
        assertEquals(105.0, p.voltage, 1e-9)      // 合計
        assertEquals(11.0, p.current, 1e-9)       // 平均
        assertEquals(150.0, p.remainingAh, 1e-9)  // 最も少ない台
        assertEquals(60.0, p.soc, 1e-9)           // 最も低い台
    }

    @Test fun extremesAcrossUnits() {
        val p = PackSummary.of(PackMode.PARALLEL, listOf(a, b))!!
        assertEquals(3.40, p.maxCellVoltage, 1e-9)
        assertEquals(3.25, p.minCellVoltage, 1e-9)
        assertEquals(0.15, p.cellVoltageDelta, 1e-9)
        assertEquals(35, p.maxTemperature)
        assertEquals(20, p.minTemperature)
        assertEquals(1, p.alarmCount)
    }

    @Test fun single() {
        val p = PackSummary.of(PackMode.SINGLE, listOf(a))!!
        assertEquals(53.0, p.voltage, 1e-9)
        assertEquals(10.0, p.current, 1e-9)
        assertEquals(200.0, p.remainingAh, 1e-9)
    }

    @Test fun empty() = assertNull(PackSummary.of(PackMode.PARALLEL, emptyList()))

    @Test fun states() {
        assertEquals(PackState.MIXED, PackSummary.combinedState(listOf(ChargeState.CHARGING, ChargeState.DISCHARGING)))
        assertEquals(PackState.CHARGING, PackSummary.combinedState(listOf(ChargeState.CHARGING, ChargeState.IDLE)))
        assertEquals(PackState.IDLE, PackSummary.combinedState(listOf(ChargeState.IDLE, ChargeState.UNKNOWN)))
    }

    @Test fun modeCodes() {
        assertEquals(PackMode.SERIES, PackMode.fromCode(2))
        assertEquals(PackMode.SINGLE, PackMode.fromCode(null))
        assertEquals(6, PackMode.PARALLEL.maxUnits)
    }
}
