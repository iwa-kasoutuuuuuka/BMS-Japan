package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** デモ用の疑似 BMS が、本物と同じ形で応答し、こちらの解析処理を通ることを確かめる */
class DemoBmsTest {
    private val bms = DemoBms()

    private fun ask(frame: ByteArray): BmsFrame = FrameAssembler().feed(bms.handle(frame)!!).single()

    @Test fun liveDataParses() {
        val r = ask(BmsCommands.READ_LIVE) as BmsFrame.ReadResponse
        val d = LiveData.parse(r.registers)
        assertEquals(16, d.cellVoltages.size)
        assertEquals(ChargeState.DISCHARGING, d.state)
        assertTrue(d.soc in 0.0..100.0)
    }

    @Test fun settingsWriteAndReadBack() {
        val ack = ask(Modbus.writeRegister(165, 0))
        assertEquals(BmsFrame.WriteSingleAck(165, 0), ack)
        val r = ask(Modbus.readRegisters(BmsSettings.MAIN_START, BmsSettings.MAIN_COUNT)) as BmsFrame.ReadResponse
        val s = BmsSettings.EMPTY.merge(BmsSettings.MAIN_START, r.registers)
        assertEquals(false, s.switch(SwitchSetting.CHARGE_MOS))
        assertEquals(3.65, s[Setting.CELL_OV]!!, 1e-9)
    }

    @Test fun passwordAndCapacity() {
        val pwd = ask(Modbus.readRegisters(BmsPassword.START, BmsPassword.COUNT)) as BmsFrame.ReadResponse
        assertEquals("123456", BmsPassword.decode(pwd.registers))
        val cap = ask(Modbus.readRegisters(ActualCapacity.REGISTER, 2, Modbus.SLAVE_AUX)) as BmsFrame.ReadResponse
        assertEquals(Modbus.SLAVE_AUX_REPLY, cap.slave)
        assertEquals(280_000, ActualCapacity.decode(cap.registers))
    }

    @Test fun historyPages() {
        val p0 = ask(Modbus.readRegisters(HistoryProtocol.pageAddress(0), HistoryProtocol.PAGE_REGISTERS)) as BmsFrame.ReadResponse
        val first = HistoryProtocol.parsePage(p0.registers)
        assertEquals(7, first.records.size)
        val p1 = ask(Modbus.readRegisters(HistoryProtocol.pageAddress(1), HistoryProtocol.PAGE_REGISTERS)) as BmsFrame.ReadResponse
        val second = HistoryProtocol.parsePage(p1.registers)
        assertEquals(5, second.records.size)
        assertTrue(second.reachedEnd)
    }
}
