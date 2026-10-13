package com.capstone.snapspot.data

import com.capstone.snapspot.core.NOW
import com.capstone.snapspot.core.PENDING_MAX_MS
import com.capstone.snapspot.core.PendingAlert
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PendingStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `held alerts survive a restart, merge by group and can be removed`() = runTest {
        val store = PendingStore(dir)
        store.add(PendingAlert("b|sb|1", "sb", 37.0, 127.0, NOW))
        store.add(PendingAlert("b|sb|2", "sb", 37.1, 127.1, NOW + 1000))
        store.add(PendingAlert("p1", "p1", 37.2, 127.2, NOW + 2000))

        val reopened = PendingStore(dir)
        assertEquals(listOf("b|sb|2", "p1"), reopened.all().map { it.regionId })
        assertEquals(NOW, reopened.all().first().since)

        reopened.remove(reopened.all().filter { it.group == "sb" })
        assertEquals(listOf("p1"), PendingStore(dir).all().map { it.regionId })
    }

    @Test
    fun `removing settled holds keeps one that arrived since`() = runTest {
        val store = PendingStore(dir)
        val old = PendingAlert("b|sb|1", "sb", 37.0, 127.0, NOW)
        store.add(old)
        val snapshot = store.all()
        store.add(PendingAlert("b|sb|2", "sb", 37.1, 127.1, NOW + PENDING_MAX_MS + 1))
        store.remove(snapshot)
        assertEquals(listOf("b|sb|2"), store.all().map { it.regionId })
    }
}
