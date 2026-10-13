package com.capstone.snapspot.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingTest {
    private val target = Coords(37.5446, 127.0565)
    private val held = PendingAlert("p1", "p1", target.latitude, target.longitude, since = NOW)

    /** `meters` north of the target. */
    private fun north(meters: Double) = Coords(target.latitude + meters / 111_320.0, target.longitude)

    @Test
    fun `15 kmh and up is a vehicle`() {
        assertFalse(isVehicleSpeed(4.1f))
        assertTrue(isVehicleSpeed(4.2f))
        assertFalse(isVehicleSpeed(1.4f))
    }

    @Test
    fun `unknown speed counts as walking`() {
        assertFalse(isVehicleSpeed(null))
    }

    @Test
    fun `walking within 1km alerts`() {
        assertEquals(PendingDecision.ALERT, decidePending(held, Fix(north(900.0), 1.2f), NOW + 3 * 60_000))
        assertEquals(PendingDecision.ALERT, decidePending(held, Fix(north(900.0), null), NOW + 3 * 60_000))
    }

    @Test
    fun `still in a vehicle checks again`() {
        assertEquals(PendingDecision.RECHECK, decidePending(held, Fix(north(300.0), 8f), NOW + 3 * 60_000))
    }

    @Test
    fun `no position checks again`() {
        assertEquals(PendingDecision.RECHECK, decidePending(held, null, NOW + 3 * 60_000))
    }

    @Test
    fun `walking more than 1km away gives up`() {
        assertEquals(PendingDecision.DROP, decidePending(held, Fix(north(1100.0), 1.2f), NOW + 3 * 60_000))
    }

    @Test
    fun `30 minutes is the last chance`() {
        assertEquals(PendingDecision.ALERT, decidePending(held, Fix(north(100.0), 1.2f), NOW + PENDING_MAX_MS))
        assertEquals(PendingDecision.DROP, decidePending(held, Fix(north(100.0), 1.2f), NOW + PENDING_MAX_MS + 1))
        assertEquals(PendingDecision.DROP, decidePending(held, Fix(north(100.0), 9f), NOW + PENDING_MAX_MS + 1))
    }

    @Test
    fun `same group merges, keeps the first since, moves the target`() {
        val a = PendingAlert("b|starbucks|1", "starbucks", 37.0, 127.0, since = NOW)
        val b = PendingAlert("b|starbucks|2", "starbucks", 37.1, 127.1, since = NOW + 60_000)
        val other = PendingAlert("p9", "p9", 37.2, 127.2, since = NOW + 30_000)
        val merged = mergePending(mergePending(mergePending(emptyList(), a), other), b)
        assertEquals(2, merged.size)
        val sb = merged.single { it.group == "starbucks" }
        assertEquals("b|starbucks|2", sb.regionId)
        assertEquals(37.1, sb.latitude)
        assertEquals(NOW, sb.since)
    }

    @Test
    fun `a hold past its 30 minutes starts over instead of passing on its old start`() {
        val stale = PendingAlert("b|starbucks|1", "starbucks", 37.0, 127.0, since = NOW)
        val fresh = PendingAlert("b|starbucks|2", "starbucks", 37.1, 127.1, since = NOW + PENDING_MAX_MS + 1)
        assertEquals(NOW + PENDING_MAX_MS + 1, mergePending(listOf(stale), fresh).single().since)
    }
}
