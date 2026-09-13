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
 * Lưu vị trí dưới: users/{uid}/locations/{id}
 * → khớp với Firebase Rules gốc của bạn:
 *   users/$user_id: .read/.write = $user_id === auth.uid
 * Không cần sửa Rules vẫn lưu được.
 */
class FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()

    private val database: FirebaseDatabase by lazy {
        val db = FirebaseDatabase.getInstance("https://ptptt-pcpc-default-rtdb.firebaseio.com")
        try {
            db.setPersistenceEnabled(true)
        } catch (_: Exception) {
            // already enabled in this process
        }
        db
    }

    val currentUser get() = auth.currentUser

    /** users/{uid}/locations */
    private fun userLocationsRef(): DatabaseReference? {
        val uid = auth.currentUser?.uid ?: return null
        return database.getReference("users").child(uid).child("locations")
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            withTimeout(15_000) {
                auth.signInWithEmailAndPassword(email, password).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "login failed", e)
            Result.failure(Exception(friendlyAuthError(e)))
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
            Result.failure(Exception(friendlyAuthError(e)))
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
     * Lưu tọa độ vào users/{uid}/locations/{pushId}
     */
    suspend fun saveLocation(item: LocationItem): Result<String> {
        return try {
            val user = auth.currentUser
                ?: return Result.failure(Exception("Chưa đăng nhập. Đăng xuất rồi đăng nhập lại."))

            val ref = userLocationsRef()
                ?: return Result.failure(Exception("Không lấy được đường dẫn lưu."))

            val key = ref.push().key
                ?: return Result.failure(Exception("Không tạo được ID."))

            // Chỉ dùng HashMap + kiểu primitive rõ ràng (tránh lỗi serialize)
            val lat = item.latitude
            val lng = item.longitude
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                return Result.failure(Exception("Tọa độ không hợp lệ: $lat, $lng"))
            }

            val data = HashMap<String, Any>()
            data["id"] = key
            data["name"] = item.name.ifBlank { "Vị trí" }
            data["latitude"] = lat
            data["longitude"] = lng
            data["userId"] = user.uid
            data["userEmail"] = user.email ?: ""
            data["timestamp"] = System.currentTimeMillis()

            withTimeout(15_000) {
                ref.child(key).setValue(data).await()
            }

            Log.i(TAG, "OK saved $key @ $lat,$lng under users/${user.uid}/locations")
            Result.success(key)
        } catch (e: Exception) {
            Log.e(TAG, "saveLocation failed", e)
            Result.failure(Exception(friendlyDbError(e)))
        }
    }

    suspend fun deleteLocation(id: String): Result<Unit> {
        return try {
            if (id.isBlank()) return Result.failure(Exception("ID rỗng"))
            val ref = userLocationsRef()
                ?: return Result.failure(Exception("Chưa đăng nhập"))
            withTimeout(10_000) {
                ref.child(id).removeValue().await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteLocation failed", e)
            Result.failure(Exception(friendlyDbError(e)))
        }
    }

    /** Lắng nghe users/{uid}/locations */
    fun observeLocations(): Flow<List<LocationItem>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = database.getReference("users").child(uid).child("locations")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val list = ArrayList<LocationItem>()
                    for (child in snapshot.children) {
                        val parsed = parseLocation(child) ?: continue
                        list.add(parsed)
                    }
                    list.sortByDescending { it.timestamp }
                    trySend(list)
                } catch (e: Exception) {
                    Log.e(TAG, "parse list error", e)
                    trySend(emptyList())
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observe cancelled: ${error.message}")
                trySend(emptyList())
            }
        }

        try {
            ref.keepSynced(true)
            ref.addValueEventListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "addListener failed", e)
            trySend(emptyList())
        }

        awaitClose {
            try {
                ref.removeEventListener(listener)
            } catch (_: Exception) {
            }
        }
    }

    private fun parseLocation(child: DataSnapshot): LocationItem? {
        return try {
            val id = child.child("id").getValue(String::class.java) ?: child.key ?: return null
            val name = child.child("name").getValue(String::class.java) ?: "Vị trí"

            // latitude / longitude có thể là Double hoặc Long tùy máy
            val lat = readDouble(child.child("latitude")) ?: return null
            val lng = readDouble(child.child("longitude")) ?: return null
            if (lat == 0.0 && lng == 0.0) return null

            val userId = child.child("userId").getValue(String::class.java) ?: ""
            val userEmail = child.child("userEmail").getValue(String::class.java) ?: ""
            val ts = child.child("timestamp").getValue(Long::class.java)
                ?: child.child("timestamp").getValue(Double::class.java)?.toLong()
                ?: 0L

            LocationItem(
                id = id,
                name = name,
                latitude = lat,
                longitude = lng,
                userId = userId,
                userEmail = userEmail,
                timestamp = ts
            )
        } catch (e: Exception) {
            Log.w(TAG, "skip node ${child.key}", e)
            null
        }
    }

    private fun readDouble(snap: DataSnapshot): Double? {
        snap.getValue(Double::class.java)?.let { return it }
        snap.getValue(Long::class.java)?.let { return it.toDouble() }
        snap.getValue(String::class.java)?.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun friendlyAuthError(e: Exception): String {
        val m = e.message ?: return "Lỗi đăng nhập"
        return when {
            m.contains("password", true) -> "Sai mật khẩu"
            m.contains("no user", true) || m.contains("USER_NOT_FOUND", true) -> "Email chưa đăng ký"
            m.contains("already", true) -> "Email đã được sử dụng"
            m.contains("network", true) -> "Không có mạng"
            m.contains("INVALID_EMAIL", true) -> "Email không hợp lệ"
            else -> m
        }
    }

    private fun friendlyDbError(e: Exception): String {
        val m = e.message ?: return "Lỗi cơ sở dữ liệu"
        return when {
            m.contains("Permission denied", true) || m.contains("PERMISSION_DENIED", true) ->
                "Không có quyền ghi. Kiểm tra đã đăng nhập và Firebase Rules (users/\$uid)."
            m.contains("network", true) || m.contains("Unable to resolve", true) ->
                "Không có mạng. Kiểm tra Internet."
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "Hết thời gian chờ. Thử lại."
            else -> m
        }
    }

    companion object {
        private const val TAG = "FirebaseRepository"
    }
}
