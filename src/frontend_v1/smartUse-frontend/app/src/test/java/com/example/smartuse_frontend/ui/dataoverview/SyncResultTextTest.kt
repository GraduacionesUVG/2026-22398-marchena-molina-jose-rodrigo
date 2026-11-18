package com.example.smartuse_frontend.ui.dataoverview

import com.example.smartuse_frontend.data.CanonicalSyncCursor
import com.example.smartuse_frontend.data.db.CanonicalSyncOutcome
import com.example.smartuse_frontend.network.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SyncResultTextTest {

    private val before = CanonicalSyncCursor(1_700_000_000_000, "aaaaaaaa-0000-5000-8000-000000000000")
    private val after = CanonicalSyncCursor(1_700_003_600_000, "bbbbbbbb-0000-5000-8000-000000000000")

    @Test
    fun `success shows the count and how far the server says we are synced`() {
        val text = syncResultText(CanonicalSyncOutcome.Success(2, before, after, batches = 1), ZoneOffset.UTC)

        assertTrue(text.isSuccess)
        assertEquals("Sincronizado: 2 registro(s)", text.headline)
        assertEquals(
            listOf(
                "Sincronizado hasta: 14/11/2023 23:13:20 (id bbbbbbbb)",
                "Antes de este intento: 14/11/2023 22:13:20 (id aaaaaaaa)",
            ),
            text.details,
        )
    }

    @Test
    fun `several batches are mentioned in the headline`() {
        val text = syncResultText(CanonicalSyncOutcome.Success(1_500, before, after, batches = 3), ZoneOffset.UTC)

        assertEquals("Sincronizado: 1500 registro(s) en 3 lotes", text.headline)
    }

    @Test
    fun `zero records is still a success, worded as nothing new`() {
        val text = syncResultText(CanonicalSyncOutcome.Success(0, after, after), ZoneOffset.UTC)

        assertTrue(text.isSuccess)
        assertEquals("Nada nuevo por sincronizar", text.headline)
    }

    @Test
    fun `a first-ever sync says there was nothing synced before`() {
        val text = syncResultText(CanonicalSyncOutcome.Success(1, null, after, batches = 1), ZoneOffset.UTC)

        assertTrue(text.details.contains("Antes de este intento: nada sincronizado aún"))
    }

    @Test
    fun `remaining data is called out`() {
        val text = syncResultText(CanonicalSyncOutcome.Success(1_000, before, after, batches = 2, hasMore = true), ZoneOffset.UTC)

        assertTrue(text.details.contains("Quedan registros por enviar; continuarán en la próxima sincronización."))
    }

    @Test
    fun `a verification note is carried as a warning line`() {
        val text = syncResultText(
            CanonicalSyncOutcome.Success(1, before, after, note = "Verificación posterior fallida: HTTP 500", batches = 1),
            ZoneOffset.UTC,
        )

        assertTrue(text.isSuccess)
        assertEquals("Aviso: Verificación posterior fallida: HTTP 500", text.details.last())
    }

    @Test
    fun `failure carries the full message and is not a success`() {
        val text = syncResultText(
            CanonicalSyncOutcome.Failed(
                "POST /sync/canonical (POST): HTTP 409 epoch_conflict: start_ts collision",
                FailureKind.PERMANENT,
            ),
            ZoneOffset.UTC,
        )

        assertFalse(text.isSuccess)
        assertEquals("Error de sincronización", text.headline)
        assertEquals(
            listOf("POST /sync/canonical (POST): HTTP 409 epoch_conflict: start_ts collision"),
            text.details,
        )
    }
}
