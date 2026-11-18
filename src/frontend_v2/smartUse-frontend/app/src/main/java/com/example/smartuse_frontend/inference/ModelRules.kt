package com.example.smartuse_frontend.inference

/** The two pattern models (models/extractor_features.md): F (frequency), S (sustained use). */
enum class Pattern { F, S }

/** Population prior of one profile's `pf`/`ps` and its pseudo-counts (extractor §6.4). */
data class Prior(val pf: Double, val ps: Double, val n0Pf: Double, val n0Ps: Double)

/**
 * The execution and extraction rules the models were validated under
 * (models/models/production.json; ModelRulesTest keeps them equal). The
 * cutoffs are not here: they come from the model files.
 */
object ModelRules {
    /** Decision points every k minutes within a session (§4). */
    const val K_MIN = 10

    /** No intervention within this many minutes of the last one shown, either pattern (§9.3). */
    const val COOLDOWN_MIN = 10

    /** At most one intervention per pattern in this many minutes (§9.3). */
    const val EPISODE_MIN = 60

    /** Interventions shown per local day, a safeguard (§9.3). */
    const val DAILY_CAP = 20

    /** Use epochs closer than this join into one session (§3.2). */
    const val SESSION_GAP_S = 30.0

    /** Sessions longer than this are `no_close`; no decision points after it (§3.3). */
    const val T_MAX_H = 3

    /** Local days with a usable point that make up the profile (§6.1). */
    const val PROFILE_DAYS = 3

    /** Quantile of the window metrics used as the label thresholds `pf`, `ps` (§6.3). */
    const val P = 0.8

    /** Width of the label and profile windows, in minutes (§6.2). */
    const val W_MIN = 60

    /** A no-use epoch longer than this is missing data, in seconds (§5). */
    const val MISSING_GAP_S = 36_000.0

    /** A local day is sufficient with epoch starts in at least this many clock hours (§5). */
    const val DAY_MIN_HOURS = 6

    val PRIOR: Map<Pattern, Prior> = mapOf(
        Pattern.F to Prior(pf = 7.772341734575637, ps = 7.551455536673661, n0Pf = 71.0, n0Ps = 247.0),
        Pattern.S to Prior(pf = 7.690528660579965, ps = 7.53131894029252, n0Pf = 71.0, n0Ps = 247.0),
    )
}
