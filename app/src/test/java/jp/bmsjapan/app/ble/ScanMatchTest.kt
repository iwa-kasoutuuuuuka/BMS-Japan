package jp.bmsjapan.app.ble

import jp.bmsjapan.app.protocol.hexToBytes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanMatchTest {

    @Test fun realLeafAdvertisementMatches() {
        // 実機 LEAF_005 のメーカー固有データ（会社ID 0x0104 = 260）。末尾 4A4842 = "JHB"
        val manufacturer = "015018050102c84a4842".hexToBytes()
        // スキャンレコードの形: [長さ][0xFF][会社ID 2 バイト][データ]
        val record = byteArrayOf((manufacturer.size + 3).toByte(), 0xFF.toByte(), 0x04, 0x01) + manufacturer
        assertTrue(BmsUuids.advContainsJhb(record))
        assertTrue(FoundDevice("50:18:05:01:02:C8", "LEAF_005", -84, advHasJhb = true).looksLikeBms)
    }

    @Test fun otherDevicesDoNotMatch() {
        assertFalse(BmsUuids.advContainsJhb("13523630315a4a4237584739503033383050".hexToBytes()))
        assertFalse(BmsUuids.advContainsJhb(null))
        assertFalse(FoundDevice("11:AA:E7:A1:47:E0", "BG02", -62).looksLikeBms)
    }

    @Test fun nameStillWorks() {
        assertTrue(FoundDevice("AA:BB:CC:DD:EE:01", "JHB-001", -60).looksLikeBms)
    }
}
