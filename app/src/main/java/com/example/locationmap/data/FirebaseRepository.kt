package com.example.locationmap.data

import android.util.Log
import com.example.locationmap.model.LocationItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Firebase Auth + Realtime Database.
 * Node mới: locations
 */
class FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()

    private val database: FirebaseDatabase by lazy {
        val db = FirebaseDatabase.getInstance("https://ptptt-pcpc-default-rtdb.firebaseio.com")
        try {
            db.setPersistenceEnabled(true)
        } catch (_: Exception) {
            // already enabled
        }
        db
    }

    private val locationsRef: DatabaseReference
        get() = database.getReference("locations")

    val currentUser get() = auth.currentUser

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            withTimeout(15_000) {
                auth.signInWithEmailAndPassword(email, password).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "login failed", e)
            Result.failure(e)
        }
    }

    suspend fun register(email: String, password: String): Result<Unit> {
        return try {
            withTimeout(15_000) {
                auth.createUserWithEmailAndPassword(email, password).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
            Result.failure(e)
        }
    }

    fun logout() {
        try {
            auth.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "logout error", e)
        }
    }

    /**
     * Lưu vị trí vào node locations/{id}
     * Dùng HashMap để tránh lỗi serialize data class trên một số máy.
     */
    suspend fun saveLocation(item: LocationItem): Result<String> {
        return try {
            val user = currentUser
                ?: return Result.failure(Exception("Chưa đăng nhập. Hãy đăng xuất rồi đăng nhập lại."))

            val key = locationsRef.push().key
                ?: return Result.failure(Exception("Không tạo được key Firebase"))

            val data = hashMapOf<String, Any>(
                "id" to key,
                "name" to item.name,
                "latitude" to item.latitude,
                "longitude" to item.longitude,
                "userId" to user.uid,
                "userEmail" to (user.email ?: ""),
                "timestamp" to System.currentTimeMillis()
            )

            withTimeout(15_000) {
                locationsRef.child(key).setValue(data).await()
            }
            Log.i(TAG, "Saved location $key: ${item.name} (${item.latitude}, ${item.longitude})")
            Result.success(key)
        } catch (e: Exception) {
            Log.e(TAG, "saveLocation failed", e)
            val msg = when {
                e.message?.contains("Permission denied", true) == true ->
                    "Permission denied – kiểm tra Firebase Rules cho node locations"
                e.message?.contains("network", true) == true ->
                    "Không có mạng. Kiểm tra Internet."
                else -> e.message ?: "Lỗi không xác định"
            }
            Result.failure(Exception(msg))
        }
    }

    suspend fun deleteLocation(id: String): Result<Unit> {
        return try {
            if (id.isBlank()) return Result.failure(Exception("ID rỗng"))
            withTimeout(10_000) {
                locationsRef.child(id).removeValue().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteLocation failed", e)
            val msg = if (e.message?.contains("Permission denied", true) == true)
                "Permission denied – kiểm tra Firebase Rules"
            else e.message ?: "Lỗi xóa"
            Result.failure(Exception(msg))
        }
    }

    fun observeLocations(): Flow<List<LocationItem>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val list = mutableListOf<LocationItem>()
                    for (child in snapshot.children) {
                        try {
                            // Đọc an toàn từng field
                            val id = child.child("id").getValue(String::class.java)
                                ?: child.key ?: continue
                            val name = child.child("name").getValue(String::class.java) ?: ""
                            val lat = child.child("latitude").getValue(Double::class.java)
                                ?: child.child("latitude").getValue(Long::class.java)?.toDouble()
                                ?: 0.0
                            val lng = child.child("longitude").getValue(Double::class.java)
                                ?: child.child("longitude").getValue(Long::class.java)?.toDouble()
                                ?: 0.0
                            val userId = child.child("userId").getValue(String::class.java) ?: ""
                            val userEmail = child.child("userEmail").getValue(String::class.java) ?: ""
                            val ts = child.child("timestamp").getValue(Long::class.java) ?: 0L

                            if (name.isNotBlank() && (lat != 0.0 || lng != 0.0)) {
                                list.add(
                                    LocationItem(
                                        id = id,
                                        name = name,
                                        latitude = lat,
                                        longitude = lng,
                                        userId = userId,
                                        userEmail = userEmail,
                                        timestamp = ts
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "skip bad node ${child.key}", e)
                        }
                    }
                    list.sortByDescending { it.timestamp }
                    trySend(list)
                } catch (e: Exception) {
                    Log.e(TAG, "onDataChange error", e)
                    trySend(emptyList())
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observe cancelled: ${error.message} code=${error.code}")
                trySend(emptyList())
            }
        }
        try {
            locationsRef.keepSynced(true)
            locationsRef.addValueEventListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "addValueEventListener failed", e)
            trySend(emptyList())
        }
        awaitClose {
            try {
                locationsRef.removeEventListener(listener)
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "FirebaseRepository"
    }
}
