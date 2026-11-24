package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [DeviceMatcher.matchTier]：三档各自的判据，以及不匹配时返回 null。 */
class DeviceMatcherTierTest {

    private val door = DoorConfig(
        mac = "AA:BB:CC:DD:EE:FF",
        key = "0011223344556677",
        bluetoothName = "BY0AABBCCDD",
    )

    @Test
    fun exactAddressWinsEvenWhenTheNameAlsoMatches() {
        val hit = ScanHit("aa:bb:cc:dd:ee:ff", "BY0AABBCCDD", -50)

        assertEquals(MatchTier.Mac, DeviceMatcher.matchTier(hit, door))
    }

    @Test
    fun nameMatchesWhenTheAddressIsDifferent() {
        assertEquals(MatchTier.Name, DeviceMatcher.matchTier(ScanHit("11:22:33:44:55:66", "by0aabbccdd", -60), door))
        assertEquals(MatchTier.Name, DeviceMatcher.matchTier(ScanHit("11:22:33:44:55:66", " BY0AABBCCDD ", -60), door))
    }

    @Test
    fun manufacturerDataIsTheWeakestTier() {
        val adv = Hex.bytesFromHex("AABBCCDDEEFF")

        assertEquals(MatchTier.AdvPayload, DeviceMatcher.matchTier(ScanHit("11:22:33:44:55:66", null, -60, adv), door))
    }

    @Test
    fun unrelatedDeviceDoesNotMatch() {
        assertNull(DeviceMatcher.matchTier(ScanHit("11:22:33:44:55:66", "别的设备", -60), door))
    }

    @Test
    fun macDerivedFromTheNameNeverMatchesByAddress() {
        // 00:00:AA:BB:CC:DD 是按蓝牙名反推的，不是真实地址：哪怕尾号相同，也不能靠地址认门锁
        val derived = door.copy(mac = "00:00:AA:BB:CC:DD")

        assertNull(DeviceMatcher.matchTier(ScanHit("C8:5C:AA:BB:CC:DD", null, -60), derived))
    }
}
