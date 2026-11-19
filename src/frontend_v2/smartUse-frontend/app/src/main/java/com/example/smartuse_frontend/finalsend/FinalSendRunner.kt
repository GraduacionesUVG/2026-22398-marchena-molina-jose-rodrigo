package com.example.smartuse_frontend.finalsend

import com.example.smartuse_frontend.forms.FormDraftStore
import com.example.smartuse_frontend.jobs.JobStateStore
import com.example.smartuse_frontend.metrics.DecisionMetric
import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.study.EndingCleanup
import com.example.smartuse_frontend.study.EndingState
import kotlinx.coroutines.CancellationException
import java.time.Clock
import kotlin.random.Random

enum class FinalSendResult { DONE, RETRY }

/**
 * One attempt of the final send: only while FORMS_DONE. 2xx
 * → finalSentAt → the cleanup; anything else → retry, 4xx included (the
 * forms are never dropped; the backoff keeps it slow). Never throws: an
 * exception here is a retry, so WorkManager never gives the send up.
 */
class FinalSendRunner(
    private val stateStore: JobStateStore,
    private val forms: FormDraftStore,
    private val readMetrics: () -> List<DecisionMetric>,
    private val authToken: suspend () -> String?,
    private val api: SmartUseApi,
    private val cleanup: EndingCleanup,
    private val clock: Clock,
    private val random: Random,
    private val logger: (String) -> Unit,
) {
    suspend fun run(): FinalSendResult = try {
        attempt()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger("final send: ${e::class.java.simpleName}: ${e.message}, retrying")
        FinalSendResult.RETRY
    }

    private suspend fun attempt(): FinalSendResult {
        val marks = stateStore.readEndMarks()
        when (marks.state) {
            EndingState.SENT -> return finish()
            EndingState.FORMS_DONE -> Unit
            else -> {
                logger("final send: nothing to send (${marks.state})")
                return FinalSendResult.DONE
            }
        }
        val completed = forms.completed()
        val token = authToken()
        if (completed == null || token == null) {
            logger("final send: no frozen forms or no token, cannot send")
            return FinalSendResult.DONE
        }
        val body = FinalSendBuilder.build(marks.reason, completed, readMetrics(), random)
        return when (val result = api.sendFinal(token, body)) {
            is NetworkResult.Success -> {
                logger("final send: confirmed (${result.body.status}), ${body.metrics.size} metric records")
                stateStore.setFinalSentAt(clock.instant())
                finish()
            }
            is NetworkResult.HttpError -> {
                logger("final send: HTTP ${result.code} ${result.errorBody?.error.orEmpty()}, retrying")
                FinalSendResult.RETRY
            }
            is NetworkResult.IoError -> {
                logger("final send: ${result.cause::class.java.simpleName}, retrying")
                FinalSendResult.RETRY
            }
        }
    }

    // The send is confirmed: a failing cleanup step is finished by the next ensure.
    private suspend fun finish(): FinalSendResult {
        try {
            cleanup.finish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger("final send: cleanup failed, the next start finishes it: ${e::class.java.simpleName}: ${e.message}")
        }
        return FinalSendResult.DONE
    }
}