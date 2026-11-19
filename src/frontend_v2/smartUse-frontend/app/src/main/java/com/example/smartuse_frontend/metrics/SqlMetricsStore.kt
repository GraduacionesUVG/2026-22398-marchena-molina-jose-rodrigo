package com.example.smartuse_frontend.metrics

import android.content.ContentValues

class SqlMetricsStore(private val helper: MetricsDbHelper) : MetricsStore {

    override fun insert(metric: DecisionMetric) {
        helper.writableDatabase.use { db ->
            db.insertOrThrow("decision_metrics", null, ContentValues().apply {
                put("decision_point", metric.decisionPoint.wire)
                put("duration_ms", metric.durationMs)
                put("battery_delta_pct", metric.batteryDeltaPct)
                put("current_mean", metric.currentMean)
                put("peak_rss_kb", metric.peakRssKb)
            })
        }
    }

    override fun count(): Int = helper.readableDatabase.use { db ->
        db.rawQuery("SELECT COUNT(*) FROM decision_metrics", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }

    override fun readAll(): List<DecisionMetric> = helper.readableDatabase.use { db ->
        db.rawQuery(
            "SELECT decision_point, duration_ms, battery_delta_pct, current_mean, peak_rss_kb FROM decision_metrics ORDER BY id",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DecisionMetric(
                            decisionPoint = DecisionKind.entries.first { it.wire == c.getString(0) },
                            durationMs = c.getLong(1),
                            batteryDeltaPct = if (c.isNull(2)) null else c.getInt(2),
                            currentMean = if (c.isNull(3)) null else c.getInt(3),
                            peakRssKb = if (c.isNull(4)) null else c.getLong(4),
                        ),
                    )
                }
            }
        }
    }
}