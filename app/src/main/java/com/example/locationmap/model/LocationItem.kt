package com.example.locationmap.model

/**
 * Model lưu vị trí trên Firebase Realtime Database (node: locations).
 * Phải có constructor không tham số + property public để Firebase serialize.
 */
data class LocationItem(
    var id: String = "",
    var name: String = "",
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var userId: String = "",
    var userEmail: String = "",
    var timestamp: Long = 0L
) {
    // Firebase yêu cầu no-arg constructor
    constructor() : this("", "", 0.0, 0.0, "", "", 0L)

    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "latitude" to latitude,
        "longitude" to longitude,
        "userId" to userId,
        "userEmail" to userEmail,
        "timestamp" to timestamp
    )
}
