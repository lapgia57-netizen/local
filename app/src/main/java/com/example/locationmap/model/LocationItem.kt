package com.example.locationmap.model

data class LocationItem(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val userId: String = "",
    val userEmail: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    // No-arg constructor for Firebase
    constructor() : this("", "", 0.0, 0.0, "", "", 0L)
}
