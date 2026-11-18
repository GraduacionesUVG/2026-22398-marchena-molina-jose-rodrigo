package com.example.smartuse_frontend.data

data class ParticipantProfile(
    val age: Int,
    val gender: String,
    val universityMajor: String,
    val sasSvScore: Int,
)

interface ProfileRepository {
    fun getProfile(): ParticipantProfile
}
