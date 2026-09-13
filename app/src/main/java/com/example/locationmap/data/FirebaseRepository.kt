package com.example.locationmap.data

import com.example.locationmap.model.LocationItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance(
        "https://ptptt-pcpc-default-rtdb.firebaseio.com"
    )
    // NEW NODE: locations
    private val locationsRef = database.getReference("locations")

    val currentUser get() = auth.currentUser

    // ========== AUTH ==========
    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, password: String): Result<Unit> {
        return try {
            auth.createUserWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        auth.signOut()
    }

    // ========== LOCATIONS ==========
    suspend fun saveLocation(item: LocationItem): Result<String> {
        return try {
            val key = locationsRef.push().key ?: return Result.failure(Exception("Cannot generate key"))
            val data = item.copy(
                id = key,
                userId = currentUser?.uid ?: "",
                userEmail = currentUser?.email ?: ""
            )
            locationsRef.child(key).setValue(data).await()
            Result.success(key)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteLocation(id: String): Result<Unit> {
        return try {
            locationsRef.child(id).removeValue().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeLocations(): Flow<List<LocationItem>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<LocationItem>()
                for (child in snapshot.children) {
                    child.getValue(LocationItem::class.java)?.let { list.add(it) }
                }
                // Sort by newest first
                list.sortByDescending { it.timestamp }
                trySend(list)
            }

            override fun onCancelled(error: DatabaseError) {
                close(error.toException())
            }
        }
        locationsRef.addValueEventListener(listener)
        awaitClose { locationsRef.removeEventListener(listener) }
    }
}
