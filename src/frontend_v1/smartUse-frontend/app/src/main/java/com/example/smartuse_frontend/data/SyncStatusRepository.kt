package com.example.smartuse_frontend.data

import java.time.Instant

interface SyncStatusRepository {
    fun lastSuccessfulCanonicalSync(): Instant?
}
