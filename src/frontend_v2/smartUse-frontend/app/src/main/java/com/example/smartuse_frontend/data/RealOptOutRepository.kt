package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.study.EndTrigger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The opt-out is the ending with its own mark (StudyEnder): the end forms and
 * the final send follow, as on day 21. Non-cancellable: leaving the screen
 * mid-call must never lose a participant's withdrawal.
 */
class RealOptOutRepository(private val endStudy: suspend (EndTrigger) -> Unit) : OptOutRepository {

    override suspend fun optOut() = withContext(NonCancellable) { endStudy(EndTrigger.OPT_OUT) }
}