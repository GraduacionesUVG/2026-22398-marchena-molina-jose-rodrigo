package com.example.smartuse_frontend.study

import com.example.smartuse_frontend.jobs.FakeJobStateStore
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.FinalSendRequest
import com.example.smartuse_frontend.network.dto.CutoffResponse
import com.example.smartuse_frontend.network.dto.RegisterRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

private class CutoffApi(private val result: () -> NetworkResult<CutoffResponse>) : SmartUseApi {
    var calls = 0
    override suspend fun getCutoff(): NetworkResult<CutoffResponse> {
        calls++
        return result()
    }
    override suspend fun register(body: RegisterRequest) = throw NotImplementedError()
    override suspend fun sendFinal(token: String, body: FinalSendRequest) = throw NotImplementedError()

}

class CutoffRefresherTest {

    private val stored = LocalDate.of(2026, 12, 1)

    private fun refresher(api: SmartUseApi, store: FakeJobStateStore) =
        CutoffRefresher(api = api, stateStore = store, logger = {})

    @Test
    fun `a published date is stored`() = runTest {
        val store = FakeJobStateStore()

        refresher(CutoffApi { NetworkResult.Success(CutoffResponse("2026-12-01")) }, store).refresh()

        assertEquals(stored, store.cutoffDate())
    }

    @Test
    fun `a null cutoff clears the stored date`() = runTest {
        val store = FakeJobStateStore(cutoffDate = stored)

        refresher(CutoffApi { NetworkResult.Success(CutoffResponse(null)) }, store).refresh()

        assertNull(store.cutoffDate())
    }

    @Test
    fun `a failed fetch keeps the stored date`() = runTest {
        val store = FakeJobStateStore(cutoffDate = stored)

        refresher(CutoffApi { NetworkResult.IoError(IOException("offline")) }, store).refresh()
        refresher(CutoffApi { NetworkResult.HttpError(400, null, "bad") }, store).refresh()
        refresher(CutoffApi { NetworkResult.HttpError(500, null, "boom") }, store).refresh()

        assertEquals(stored, store.cutoffDate())
    }

    @Test
    fun `an unparseable date keeps the stored date`() = runTest {
        val store = FakeJobStateStore(cutoffDate = stored)

        refresher(CutoffApi { NetworkResult.Success(CutoffResponse("2026-12-15T00:00:00Z")) }, store).refresh()
        refresher(CutoffApi { NetworkResult.Success(CutoffResponse("mañana")) }, store).refresh()

        assertEquals(stored, store.cutoffDate())
    }

    @Test
    fun `an exception from the client never escapes and keeps the stored date`() = runTest {
        val store = FakeJobStateStore(cutoffDate = stored)

        refresher(CutoffApi { throw IllegalStateException("boom") }, store).refresh()

        assertEquals(stored, store.cutoffDate())
    }

    @Test
    fun `nothing is fetched once opted out`() = runTest {
        val api = CutoffApi { NetworkResult.Success(CutoffResponse("2027-01-01")) }
        val store = FakeJobStateStore(optedOutAt = Instant.parse("2026-11-25T10:00:00Z"), cutoffDate = stored)

        refresher(api, store).refresh()

        assertEquals(0, api.calls)
        assertEquals(stored, store.cutoffDate())
    }

    @Test
    fun `nothing is fetched once collection has ended`() = runTest {
        val api = CutoffApi { NetworkResult.Success(CutoffResponse("2027-01-01")) }
        val store = FakeJobStateStore(collectionEndedAt = Instant.parse("2026-12-01T06:00:00Z"))

        refresher(api, store).refresh()

        assertEquals(0, api.calls)
    }

    @Test
    fun `refresh reports a change only when the stored date changed`() = runTest {
        val store = FakeJobStateStore()

        assertTrue(refresher(CutoffApi { NetworkResult.Success(CutoffResponse("2026-12-01")) }, store).refresh())
        assertFalse(refresher(CutoffApi { NetworkResult.Success(CutoffResponse("2026-12-01")) }, store).refresh())
        assertTrue(refresher(CutoffApi { NetworkResult.Success(CutoffResponse(null)) }, store).refresh())
        assertFalse(refresher(CutoffApi { NetworkResult.Success(CutoffResponse(null)) }, store).refresh())
    }

    @Test
    fun `a failed, unparseable or skipped refresh reports no change`() = runTest {
        val store = FakeJobStateStore(cutoffDate = stored)

        assertFalse(refresher(CutoffApi { NetworkResult.IoError(IOException("offline")) }, store).refresh())
        assertFalse(refresher(CutoffApi { NetworkResult.Success(CutoffResponse("mañana")) }, store).refresh())
        val optedOut = FakeJobStateStore(optedOutAt = Instant.parse("2026-11-25T10:00:00Z"))
        assertFalse(refresher(CutoffApi { NetworkResult.Success(CutoffResponse("2027-01-01")) }, optedOut).refresh())
    }
}
