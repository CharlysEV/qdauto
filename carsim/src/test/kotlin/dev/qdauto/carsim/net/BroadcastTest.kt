package dev.qdauto.carsim.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BroadcastTest {
    @Test
    fun defaultTargetsStartWithTheLimitedBroadcast() {
        val targets = BroadcastTargets.resolve(emptyList())
        assertEquals(BroadcastTargets.LIMITED, targets.first().address)
        assertEquals(targets.size, targets.map { it.address }.distinct().size)
        assertTrue(targets.drop(1).all { it.address.address.size == 4 }, targets.toString())
    }

    @Test
    fun explicitTargetsKeepOrderWithoutDuplicates() {
        val targets = BroadcastTargets.resolve(listOf("127.0.0.1", "broadcast", "127.0.0.1"))
        assertEquals("127.0.0.1", targets[0].address.hostAddress)
        assertEquals(BroadcastTargets.LIMITED, targets[1].address)
        assertEquals(1, targets.count { it.address.hostAddress == "127.0.0.1" })
    }

    @Test
    fun fanoutSendsWhileActive() {
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 5_000
            val active = AtomicBoolean(true)
            val payload = "Connect_Broadcast de prueba".toByteArray()
            val fanout = BroadcastFanout(listOf(InetSocketAddress(loopback, receiver.localPort)), payload, 50, active::get) { }.start()
            try {
                val packet = DatagramPacket(ByteArray(256), 256)
                receiver.receive(packet)
                assertEquals(String(payload), String(packet.data, 0, packet.length))
                active.set(false)
            } finally {
                fanout.close()
            }
            assertTrue(fanout.sent >= 1)
        }
    }
}
