package com.example.smartuse_frontend.inference

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Both models' scores at one decision point, with their cutoffs. Never stored. */
data class Scores(val f: Double, val s: Double, val fCutoff: Double, val sCutoff: Double)

/**
 * Control state of the execution rules: when each pattern
 * was last shown and how many were shown on [shownDay]. Lets the app run under
 * the rules the cutoffs were tuned for; never sent, never analysed.
 */
data class FiringState(
    val lastShownF: Instant? = null,
    val lastShownS: Instant? = null,
    val shownDay: LocalDate? = null,
    val shownToday: Int = 0,
)

/**
 * models/extractor_features.md §9.3: each model fires above its own cutoff;
 * nothing within 10 min of the last intervention; a pattern not within 60 min
 * of its own last one; at most 20 per local day. Among the patterns that fired
 * and may show, the larger margin over its cutoff wins, F on a tie.
 */
fun choosePattern(scores: Scores, now: Instant, zone: ZoneId, state: FiringState): Pattern? {
    val last = listOfNotNull(state.lastShownF, state.lastShownS).maxOrNull()
    if (last != null && now < last.plus(Duration.ofMinutes(ModelRules.COOLDOWN_MIN.toLong()))) return null
    if (state.shownDay == now.atZone(zone).toLocalDate() && state.shownToday >= ModelRules.DAILY_CAP) return null
    fun allowed(lastOfPattern: Instant?) =
        lastOfPattern == null || now >= lastOfPattern.plus(Duration.ofMinutes(ModelRules.EPISODE_MIN.toLong()))
    val fMargin = (scores.f - scores.fCutoff).takeIf { it > 0 && allowed(state.lastShownF) }
    val sMargin = (scores.s - scores.sCutoff).takeIf { it > 0 && allowed(state.lastShownS) }
    return when {
        fMargin != null && (sMargin == null || fMargin >= sMargin) -> Pattern.F
        sMargin != null -> Pattern.S
        else -> null
    }
}

/** The state after [pattern] was actually shown at [now]. */
fun FiringState.afterShown(pattern: Pattern, now: Instant, zone: ZoneId): FiringState {
    val today = now.atZone(zone).toLocalDate()
    val count = if (shownDay == today) shownToday + 1 else 1
    return when (pattern) {
        Pattern.F -> copy(lastShownF = now, shownDay = today, shownToday = count)
        Pattern.S -> copy(lastShownS = now, shownDay = today, shownToday = count)
    }
}
