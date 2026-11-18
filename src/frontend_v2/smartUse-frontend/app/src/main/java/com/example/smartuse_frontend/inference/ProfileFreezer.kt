package com.example.smartuse_frontend.inference

import java.time.Duration

/**
 * Freezes the two profiles once their 3 days are covered (models/extractor_features.md
 * §6.1): run by the cycle after capture until it succeeds. Until then there is no
 * inference.
 *
 * @param epochs every captured epoch, in order; the last one is the dangling
 *   epoch, so the data ends where it starts (as profilePoints reads it).
 * @param forcedAt signup + [FORCED_AFTER] (epoch s): from then on, if the 3 days
 *   aren't there, the profiles freeze from whatever data exists (forcedTiers).
 *   Null: never forced.
 */
class ProfileFreezer(
    private val store: ProfileStore,
    private val epochs: () -> List<ModelEpoch>,
    private val offsetS: () -> Double,
    private val forcedAt: Double?,
    private val logger: (String) -> Unit,
) {
    fun isFrozen(): Boolean = store.read(Pattern.F) != null && store.read(Pattern.S) != null

    /** Profile days found so far (debug card): 3 once frozen. */
    fun daysSoFar(): Int = if (isFrozen()) ModelRules.PROFILE_DAYS else epochs().let { if (it.isEmpty()) 0 else days(it, buildSessions(it), offsetS()).size }

    /** True once both profiles are frozen (now or before); [now] in epoch s. */
    fun tryFreeze(now: Double): Boolean {
        if (isFrozen()) return true
        val forced = forcedAt != null && now >= forcedAt
        val all = epochs()
        if (all.isEmpty()) {
            logger(if (forced) "profile: no data yet (forced)" else "profile: no data yet")
            return false
        }
        val offset = offsetS()
        val sessions = buildSessions(all)
        val points = profilePoints(all, sessions, offset, labelOffsetMin(Pattern.F))
        val days = profileDays(points, offset)
        if (days.size == ModelRules.PROFILE_DAYS && all.last().start >= profileReadyAt(days, offset)) {
            val f = buildProfile(Pattern.F, all, sessions, offset)
            val s = buildProfile(Pattern.S, all, sessions, offset)
            if (f != null && s != null) {
                store.freeze(f, s)
                logger("profile: frozen (${f.profileDays.joinToString()})")
                return true
            }
        }
        if (forced) {
            // The first tier where both profiles build: never one alone.
            for (tier in forcedTiers(points, offset, all.last().start)) {
                val f = buildProfile(Pattern.F, all, sessions, offset, tier.days, tier.keep) ?: continue
                val s = buildProfile(Pattern.S, all, sessions, offset, tier.days, tier.keep) ?: continue
                store.freeze(f, s)
                logger("profile: frozen (forced, ${tier.days.size} days)")
                return true
            }
            logger("profile: forced, no points yet")
            return false
        }
        val waiting = if (days.size == ModelRules.PROFILE_DAYS) ", waiting for the last window" else ""
        logger("profile: ${days.size} of ${ModelRules.PROFILE_DAYS} days$waiting")
        return false
    }

    private fun days(all: List<ModelEpoch>, sessions: List<Session>, offset: Double) =
        profileDays(profilePoints(all, sessions, offset, labelOffsetMin(Pattern.F)), offset)

    companion object {
        /** The forced freeze: 3 days after installing (the user's decision, 2026-10-20). */
        val FORCED_AFTER: Duration = Duration.ofHours(72)
    }
}
