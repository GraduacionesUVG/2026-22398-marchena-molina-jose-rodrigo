package com.example.smartuse_frontend.jobs

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class OptOutFinishRequestTest {

    @Test
    fun `opt-out finish retries back off linearly every 10 minutes and need the network`() {
        val spec = optOutFinishRequest().workSpec

        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(10 * 60 * 1000L, spec.backoffDelayDuration)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(OptOutFinishWorker::class.java.name, spec.workerClassName)
    }
}
