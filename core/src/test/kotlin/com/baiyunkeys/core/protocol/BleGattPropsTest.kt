package com.baiyunkeys.core.protocol

import com.baiyunkeys.core.protocol.BleGattProps.PROPERTY_INDICATE
import com.baiyunkeys.core.protocol.BleGattProps.PROPERTY_NOTIFY
import com.baiyunkeys.core.protocol.BleGattProps.PROPERTY_READ
import com.baiyunkeys.core.protocol.BleGattProps.PROPERTY_WRITE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleGattPropsTest {

    @Test
    fun notifyIsPreferredWhenTheCharacteristicSupportsBoth() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            BleGattProps.cccdValue(PROPERTY_NOTIFY or PROPERTY_INDICATE or PROPERTY_READ),
        )
    }

    @Test
    fun indicateOnlyCharacteristicGetsTheIndicationValue() {
        // 给这种特征写通知值会被拒绝，门锁的回执就收不到
        assertArrayEquals(byteArrayOf(0x02, 0x00), BleGattProps.cccdValue(PROPERTY_INDICATE or PROPERTY_READ))
    }

    @Test
    fun characteristicWithoutNotificationsHasNothingToWrite() {
        assertNull(BleGattProps.cccdValue(PROPERTY_READ or PROPERTY_WRITE))
        assertNull(BleGattProps.cccdValue(0))
    }
}
