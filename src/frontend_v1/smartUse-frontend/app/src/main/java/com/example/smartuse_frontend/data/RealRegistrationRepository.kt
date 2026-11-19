package com.example.smartuse_frontend.data

import com.example.smartuse_frontend.network.NetworkResult
import com.example.smartuse_frontend.network.SmartUseApi
import com.example.smartuse_frontend.network.dto.RegisterRequest
import java.time.Clock

class RealRegistrationRepository(
    private val api: SmartUseApi,
    private val clock: Clock = Clock.systemUTC(),
) : RegistrationRepository {

    override suspend fun register(draft: SetupDraft): RegisterOutcome {
        val group = draft.resolvedGroup
            ?: return RegisterOutcome.Rejected("No se pudo determinar la configuración del estudio.")

        val request = RegisterRequest(
            ageAtSignup = draft.age,
            gender = draft.gender,
            universityMajor = draft.universityMajor,
            testScore = draft.sasSvScore,
            experimentGroup = group.wireValue,
            experimentConfig = null, // control group: omitted from the wire
        )

        return when (val result = api.register(request)) {
            is NetworkResult.Success -> RegisterOutcome.Registered(
                userId = result.body.userId,
                authToken = result.body.authToken,
                signupTs = clock.instant(),
            )

            is NetworkResult.HttpError ->
                if (result.code == 400) {
                    RegisterOutcome.Rejected(
                        result.errorBody?.message ?: "El servidor rechazó el registro.",
                    )
                } else {
                    RegisterOutcome.Retryable
                }

            is NetworkResult.IoError -> RegisterOutcome.Retryable
        }
    }
}
