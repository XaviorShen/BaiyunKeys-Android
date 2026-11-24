package com.baiyunkeys.core.protocol

/**
 * 特征属性位与订阅值。
 *
 * 属性位的取值与 `android.bluetooth.BluetoothGattCharacteristic.PROPERTY_*` 一致。
 * 放在 core 里是为了能脱离 Android 单测；app 层直接拿特征的 `properties` 来问。
 */
object BleGattProps {

    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_READ = 0x02
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20

    /** 写进 CCCD 的值：开启通知。 */
    val ENABLE_NOTIFICATION_VALUE: ByteArray = byteArrayOf(0x01, 0x00)

    /** 写进 CCCD 的值：开启指示。 */
    val ENABLE_INDICATION_VALUE: ByteArray = byteArrayOf(0x02, 0x00)

    /**
     * 订阅时该往 CCCD 写哪个值：通知优先，其次指示（SafeBaiyun、RinkTika 的取法）。
     *
     * 只支持指示的特征如果写了通知值，会被门锁拒绝，结果是收不到回执。
     * 两种都不支持时返回 null。
     */
    fun cccdValue(properties: Int): ByteArray? = when {
        properties and PROPERTY_NOTIFY != 0 -> ENABLE_NOTIFICATION_VALUE
        properties and PROPERTY_INDICATE != 0 -> ENABLE_INDICATION_VALUE
        else -> null
    }
}
