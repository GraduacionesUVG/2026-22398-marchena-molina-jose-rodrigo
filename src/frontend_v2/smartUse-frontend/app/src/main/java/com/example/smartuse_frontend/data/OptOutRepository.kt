package com.example.smartuse_frontend.data

/** The one opt-out: it starts the same ending as day 21 (end forms, then the final send). */
interface OptOutRepository {
    suspend fun optOut()
}
