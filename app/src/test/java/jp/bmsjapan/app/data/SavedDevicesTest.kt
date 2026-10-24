package jp.bmsjapan.app.data

import jp.bmsjapan.app.protocol.PackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedDevicesTest {

    @Test fun roundTrip() {
        val setup = SavedSetup(
            PackMode.PARALLEL,
            listOf(SavedDevice("AA:BB:CC:DD:EE:01", "JHB-001"), SavedDevice("AA:BB:CC:DD:EE:02", "JHB 倉庫")),
        )
        assertEquals(setup, SavedDevicesCodec.decode(SavedDevicesCodec.encode(setup)))
    }

    @Test fun nameWithTabAndNewlineIsSanitized() {
        val setup = SavedSetup(PackMode.SINGLE, listOf(SavedDevice("AA:BB:CC:DD:EE:01", "a\tb\nc")))
        val back = SavedDevicesCodec.decode(SavedDevicesCodec.encode(setup))!!
        assertEquals("a b c", back.devices.single().name)
    }

    @Test fun emptyOrBrokenInput() {
        assertNull(SavedDevicesCodec.decode(null))
        assertNull(SavedDevicesCodec.decode(""))
        val broken = SavedDevicesCodec.decode("x\nnot-a-mac\tfoo\nAA:BB:CC:DD:EE:01")!!
        assertEquals(PackMode.SINGLE, broken.mode)              // 読めないコードは単体
        assertEquals(1, broken.devices.size)
        assertEquals("AA:BB:CC:DD:EE:01", broken.devices[0].name) // 名前が無ければアドレス
    }

    @Test fun limitedToModeMaximumAndDeduplicated() {
        val text = "0\nAA:BB:CC:DD:EE:01\tA\nAA:BB:CC:DD:EE:02\tB"
        assertEquals(1, SavedDevicesCodec.decode(text)!!.devices.size) // 単体は 1 台まで
        val dup = "1\naa:bb:cc:dd:ee:01\tA\nAA:BB:CC:DD:EE:01\tA2"
        assertEquals(1, SavedDevicesCodec.decode(dup)!!.devices.size)
    }

    @Test fun emptySetup() {
        assertTrue(SavedDevicesCodec.decode("2")!!.isEmpty)
    }
}
