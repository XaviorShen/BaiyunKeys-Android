package com.baiyunkeys.core.protocol

import java.util.UUID

/**
 * 门锁用到的 GATT UUID。
 *
 * 两个厂商服务 UUID 来自四个公开实现：`14839ac4-…` 是 SafeBaiyun 与 yy1643 唯一认的服务，
 * RinkTika、小程序 Soufock 和网页版两个都认。本仓库此前不认任何 UUID，只按「同一服务里
 * 同时有读 + 写特征」去找，这里把已知的排在前面作为优先项。
 */
object BleUuids {

    val VENDOR_SERVICES: List<UUID> = listOf(
        UUID.fromString("14839ac4-7d7e-415c-9a42-167340cf2339"),
        UUID.fromString("0734594a-a8e7-4b1a-a6b1-cd5243059a57"),
    )

    /** 把已知厂商服务排到前面，其余保持原顺序。 */
    fun <T> vendorFirst(services: List<T>, uuidOf: (T) -> UUID): List<T> =
        services.sortedBy { if (uuidOf(it) in VENDOR_SERVICES) 0 else 1 }
}
