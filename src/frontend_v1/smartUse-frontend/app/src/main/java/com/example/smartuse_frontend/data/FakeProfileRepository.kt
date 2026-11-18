package com.example.smartuse_frontend.data

class FakeProfileRepository(
    private val profile: ParticipantProfile = ParticipantProfile(
        age = 21,
        gender = "female",
        universityMajor = "Computer Science",
        sasSvScore = 33,
    ),
) : ProfileRepository {
    override fun getProfile(): ParticipantProfile = profile
}
