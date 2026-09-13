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
 * Firebase Auth + Realtime Database repository.
 * Node: locations (mới)
 * Designed to not crash the app when offline or rules fail.
 */
class FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()
    private val database: FirebaseDatabase = try {
        FirebaseDatabase.getInstance("https://ptptt-pcpc-default-rtdb.firebaseio.com").also {
            it.setPersistenceEnabled(true) // offline cache
        }
    } catch (e: Exception) {
        // Already enabled or other issue
        FirebaseDatabase.getInstance("https://ptptt-pcpc-default-rtdb.firebaseio.com")
    }

    private val locationsRef = database.getReference("locations")

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

    suspend fun saveLocation(item: LocationItem): Result<String> {
        return try {
            val user = currentUser ?: return Result.failure(Exception("Chưa đăng nhập"))
            val key = locationsRef.push().key
                ?: return Result.failure(Exception("Không tạo được key"))
            val data = item.copy(
                id = key,
                userId = user.uid,
                userEmail = user.email ?: "",
                timestamp = System.currentTimeMillis()
            )
            withTimeout(12_000) {
                locationsRef.child(key).setValue(data).await()
            }
            Result.success(key)
        } catch (e: Exception) {
            Log.e(TAG, "saveLocation failed", e)
            Result.failure(e)
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
            Result.failure(e)
        }
    }

    fun observeLocations(): Flow<List<LocationItem>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val list = mutableListOf<LocationItem>()
                    for (child in snapshot.children) {
                        try {
                            child.getValue(LocationItem::class.java)?.let { list.add(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "skip bad location node: ${child.key}", e)
                        }
                    }
                    list.sortByDescending { it.timestamp }
                    trySend(list)
                } catch (e: Exception) {
                    Log.e(TAG, "onDataChange parse error", e)
                    trySend(emptyList())
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observe cancelled: ${error.message}")
                // Don't close flow hard – keep listening if possible
                trySend(emptyList())
            }
        }
        try {
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
