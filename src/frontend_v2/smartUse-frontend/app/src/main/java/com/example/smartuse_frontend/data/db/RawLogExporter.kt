package com.example.smartuse_frontend.data.db

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Lets a participant export their own raw capture log — transparency, not a
 * debug tool. `app_hash` values are one-way keyed hashes (HMAC-SHA256 with a
 * per-install Keystore key); exporting them never reveals which apps
 * were used.
 */
/** In the cache dir; a copy of usage data, so the end erases it (LocalDataWiper). */
internal const val RAW_LOG_EXPORT_FILE = "raw_capture_log_export.csv"

suspend fun exportRawLogAsCsv(context: Context): File {
    val file = File(context.cacheDir, RAW_LOG_EXPORT_FILE)
    RawCaptureDbHelper(context).readableDatabase.use { db ->
        db.rawQuery(
            "SELECT id, event_ts, event_type, app_hash FROM raw_capture_log ORDER BY id",
            null,
        ).use { cursor ->
            file.bufferedWriter().use { writer ->
                writer.write("id,event_ts,event_type,app_hash")
                writer.newLine()
                while (cursor.moveToNext()) {
                    writer.write(
                        "${cursor.getLong(0)},${cursor.getLong(1)},${cursor.getString(2)},${cursor.getString(3) ?: ""}",
                    )
                    writer.newLine()
                }
            }
        }
    }
    return file
}

fun buildRawLogShareIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(sendIntent, "Exportar registro de datos")
}
