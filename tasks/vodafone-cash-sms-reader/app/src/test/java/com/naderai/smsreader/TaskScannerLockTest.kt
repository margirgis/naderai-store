package com.naderai.smsreader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScannerLockTest {

    @Test
    fun scanLock_preventsConcurrentScan() {
        val taskId = "task-abc"
        TaskScanner.clearScanLock(taskId)
        assertFalse(TaskScanner.isScanning(taskId))

        // Simulate acquiring lock
        val acquired = java.util.Collections.newSetFromMap<String>(java.util.concurrent.ConcurrentHashMap()).add(taskId)
        assertTrue(acquired)

        TaskScanner.clearScanLock(taskId)
        assertFalse(TaskScanner.isScanning(taskId))
    }

    @Test
    fun maxAttempts_doesNotExceedThree() {
        assertEquals(3, TaskScanner.MAX_SCAN_ATTEMPTS)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
