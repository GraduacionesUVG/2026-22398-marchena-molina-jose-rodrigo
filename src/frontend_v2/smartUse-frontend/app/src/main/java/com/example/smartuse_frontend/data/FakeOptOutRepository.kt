package com.example.smartuse_frontend.data

class FakeOptOutRepository : OptOutRepository {
    var calls = 0
        private set

    override suspend fun optOut() {
        calls++
    }
}
