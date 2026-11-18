package com.example.smartuse_frontend.inference

import android.content.Context

/** Both pattern models, parsed. */
class LoadedModels(val f: SmartUseModel, val s: SmartUseModel)

/**
 * The bundled models (assets/F.model, assets/S.model), parsed once per
 * process and kept: decision points come every few minutes and the files are
 * ~1 MB of text each. A failed load throws and is not cached, so the next
 * decision point tries again.
 */
object Models {
    @Volatile private var loaded: LoadedModels? = null

    fun get(context: Context): LoadedModels = loaded ?: synchronized(this) {
        loaded ?: LoadedModels(load(context, Pattern.F), load(context, Pattern.S)).also { loaded = it }
    }

    private fun load(context: Context, pattern: Pattern): SmartUseModel =
        SmartUseModel.load(context.assets.open("${pattern.name}.model").reader()).also {
            check(it.pattern() == pattern.name) { "${pattern.name}.model holds pattern ${it.pattern()}" }
        }
}
