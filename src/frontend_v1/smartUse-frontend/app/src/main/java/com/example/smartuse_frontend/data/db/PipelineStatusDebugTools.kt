package com.example.smartuse_frontend.data.db

import android.content.Context

// TEMP — debug only. Read-only snapshot of each pipeline stage's on-device
// state, for the "Perfil" tab's stage badges.
data class PipelineStageStatus(val rowCount: Int, val cursorLabel: String, val cursorValue: Long?)

fun readRawLogStatus(context: Context): PipelineStageStatus {
    val helper = RawCaptureDbHelper(context)
    return helper.readableDatabase.use { db ->
        val count = db.rawQuery("SELECT COUNT(*) FROM raw_capture_log", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        val cursor = db.rawQuery("SELECT last_event_ts FROM capture_cursor WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        PipelineStageStatus(count, "capture_cursor", cursor)
    }
}

fun readCanonicalStatus(context: Context): PipelineStageStatus {
    val helper = CanonicalDbHelper(context)
    return helper.readableDatabase.use { db ->
        val count = db.rawQuery("SELECT COUNT(*) FROM epochs", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
        val cursor = db.rawQuery("SELECT last_raw_log_id FROM epoch_cursor WHERE id = 1", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        PipelineStageStatus(count, "epoch_cursor", cursor)
    }
}
