package com.baiyunkeys.core.protocol

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class BleUuidsTest {

    private val vendorA = UUID.fromString("14839ac4-7d7e-415c-9a42-167340cf2339")
    private val vendorB = UUID.fromString("0734594a-a8e7-4b1a-a6b1-cd5243059a57")
    private val other1 = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val other2 = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    @Test
    fun vendorServicesMoveToTheFrontAndKeepTheirRelativeOrder() {
        val ordered = BleUuids.vendorFirst(listOf(other1, vendorB, other2, vendorA)) { it }

        assertEquals(listOf(vendorB, vendorA, other1, other2), ordered)
    }

    @Test
    fun orderIsUnchangedWithoutVendorServices() {
        val services = listOf(other2, other1)

        assertEquals(services, BleUuids.vendorFirst(services) { it })
        assertEquals(emptyList<UUID>(), BleUuids.vendorFirst(emptyList<UUID>()) { it })
    }

    @Test
    fun bothUuidsFromTheReferenceImplementationsAreListed() {
        assertEquals(listOf(vendorA, vendorB), BleUuids.VENDOR_SERVICES)
    }
}
