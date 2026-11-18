package com.example.smartuse_frontend.inference

/**
 * One epoch as the extractor sees it (models/extractor_features.md §3.1), in
 * epoch seconds UTC. [open] marks the epoch running at the inference time: its
 * apps are not known yet, so it is never dropped for having 0 apps.
 */
data class ModelEpoch(val start: Double, val end: Double, val isUse: Boolean, val apps: Int, val open: Boolean = false)

/**
 * A session (§3.2): use epochs with at least one app, joined when less than
 * 30 s apart. [apps] is the sum over its epochs (an upper bound: the same app
 * in two epochs can't be told apart). [noClose]: longer than 3 h (§3.3).
 */
data class Session(val start: Double, val end: Double, val apps: Int, val nEpochs: Int, val noClose: Boolean)

/** §3.1: consecutive epochs of the same type become one: first start, last end, apps = max. */
fun mergeEpochs(epochs: List<ModelEpoch>): List<ModelEpoch> {
    val merged = mutableListOf<ModelEpoch>()
    for (e in epochs) {
        val last = merged.lastOrNull()
        if (last != null && last.isUse == e.isUse) {
            merged[merged.lastIndex] = last.copy(end = e.end, apps = maxOf(last.apps, e.apps), open = last.open || e.open)
        } else {
            merged += e
        }
    }
    return merged
}

/** §3.2-3.3, in that order: merge, drop closed 0-app use epochs, join use epochs less than 30 s apart. */
fun buildSessions(epochs: List<ModelEpoch>): List<Session> {
    val uses = mergeEpochs(epochs).filter { it.isUse && (it.apps > 0 || it.open) }
    val sessions = mutableListOf<Session>()
    for (e in uses) {
        val last = sessions.lastOrNull()
        if (last != null && e.start - last.end < ModelRules.SESSION_GAP_S) {
            sessions[sessions.lastIndex] = last.copy(end = e.end, apps = last.apps + e.apps, nEpochs = last.nEpochs + 1)
        } else {
            sessions += Session(e.start, e.end, e.apps, 1, false)
        }
    }
    val tMax = ModelRules.T_MAX_H * 3600.0
    return sessions.map { it.copy(noClose = it.end - it.start > tMax) }
}
