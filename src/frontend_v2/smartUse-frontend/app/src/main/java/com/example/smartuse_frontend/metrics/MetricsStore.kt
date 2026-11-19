package com.example.smartuse_frontend.metrics

/** The local performance-metrics store. Read only by the final send and the debug count. */
interface MetricsStore {
    fun insert(metric: DecisionMetric)
    fun count(): Int

    /** Every record, oldest first; the final send shuffles them. */
    fun readAll(): List<DecisionMetric>
}
