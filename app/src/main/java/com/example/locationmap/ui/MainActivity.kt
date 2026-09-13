package com.example.locationmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.locationmap.R
import com.example.locationmap.data.FirebaseRepository
import com.example.locationmap.databinding.ActivityMainBinding
import com.example.locationmap.databinding.DialogAddLocationBinding
import com.example.locationmap.model.LocationItem
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Main map screen – OpenStreetMap (OSM) via osmdroid.
 * Designed for stability on real devices (Android 10–14).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo = FirebaseRepository()
    private lateinit var adapter: LocationAdapter
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val markers = mutableMapOf<String, Marker>()
    private var bottomSheetVisible = false
    private var isMapReady = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            enableMyLocation()
        } else {
            safeToast(getString(R.string.permission_required))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            if (repo.currentUser == null) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)

            setupMap()
            setupRecycler()
            setupButtons()
            observeLocations()
            requestLocationPermission()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed", e)
            safeToast("Lỗi khởi tạo: ${e.message}")
        }
    }

    private fun setupMap() {
        try {
            binding.mapView.apply {
                // OpenStreetMap chuẩn (HTTPS)
                setTileSource(createOsmTileSource())

                zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                isHorizontalMapRepetitionEnabled = false
                isVerticalMapRepetitionEnabled = false

                minZoomLevel = 4.0
                maxZoomLevel = 19.0
                controller.setZoom(14.0)
                // Default center: Vietnam (Hanoi)
                controller.setCenter(GeoPoint(21.0285, 105.8542))
            }

            // Long-press → add location
            val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let { showAddLocationDialog(it.latitude, it.longitude) }
                    return true
                }
            })
            binding.mapView.overlays.add(0, eventsOverlay)
            binding.mapView.invalidate()
            isMapReady = true
        } catch (e: Exception) {
            Log.e(TAG, "setupMap failed", e)
            safeToast("Không thể tải bản đồ. Kiểm tra Internet.")
        }
    }

    /**
     * Bản đồ OpenStreetMap chuẩn (không dùng địa hình).
     */
    private fun createOsmTileSource(): OnlineTileSourceBase {
        return XYTileSource(
            "MAPNIK",
            0, 19, 256, ".png",
            arrayOf(
                "https://tile.openstreetmap.org/",
                "https://a.tile.openstreetmap.org/",
                "https://b.tile.openstreetmap.org/",
                "https://c.tile.openstreetmap.org/"
            ),
            "© OpenStreetMap contributors"
        )
    }

    private fun setupRecycler() {
        adapter = LocationAdapter(
            onItemClick = { item ->
                if (!isMapReady) return@LocationAdapter
                try {
                    binding.mapView.controller.animateTo(GeoPoint(item.latitude, item.longitude))
                    binding.mapView.controller.setZoom(17.0)
                    hideBottomSheet()
                } catch (e: Exception) {
                    Log.e(TAG, "move to location failed", e)
                }
            },
            onDeleteClick = { item -> confirmDelete(item) }
        )
        binding.rvLocations.layoutManager = LinearLayoutManager(this)
        binding.rvLocations.adapter = adapter
    }

    private fun setupButtons() {
        binding.fabMyLocation.setOnClickListener { centerOnMyLocation() }
        binding.fabAddLocation.setOnClickListener {
            getCurrentLocation { lat, lng -> showAddLocationDialog(lat, lng) }
        }
        binding.tvHint.setOnClickListener { binding.tvHint.visibility = View.GONE }
    }

    private fun observeLocations() {
        lifecycleScope.launch {
            repo.observeLocations()
                .catch { e ->
                    Log.e(TAG, "observeLocations error", e)
                    withContext(Dispatchers.Main) {
                        safeToast("Lỗi tải danh sách: ${e.message}")
                    }
                }
                .collectLatest { list ->
                    try {
                        adapter.submitList(list)
                        binding.tvEmpty.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                        updateMarkers(list)
                    } catch (e: Exception) {
                        Log.e(TAG, "update UI failed", e)
                    }
                }
        }
    }

    private fun updateMarkers(list: List<LocationItem>) {
        if (!isMapReady) return
        try {
            val currentIds = list.map { it.id }.toSet()
            markers.keys.filter { it !in currentIds }.forEach { id ->
                markers.remove(id)?.let { binding.mapView.overlays.remove(it) }
            }
            list.forEach { item ->
                if (item.id !in markers) {
                    val marker = Marker(binding.mapView).apply {
                        position = GeoPoint(item.latitude, item.longitude)
                        title = item.name
                        snippet = String.format("%.5f, %.5f", item.latitude, item.longitude)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker)
                    }
                    binding.mapView.overlays.add(marker)
                    markers[item.id] = marker
                }
            }
            binding.mapView.invalidate()
        } catch (e: Exception) {
            Log.e(TAG, "updateMarkers failed", e)
        }
    }

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enableMyLocation() {
        if (!isMapReady) return
        try {
            myLocationOverlay?.let { binding.mapView.overlays.remove(it) }
            myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
                enableMyLocation()
                disableFollowLocation()
            }
            binding.mapView.overlays.add(myLocationOverlay)
            binding.mapView.invalidate()
        } catch (e: Exception) {
            Log.e(TAG, "enableMyLocation failed", e)
        }
    }

    private fun centerOnMyLocation() {
        val loc = myLocationOverlay?.myLocation
        if (loc != null) {
            try {
                binding.mapView.controller.animateTo(loc)
                binding.mapView.controller.setZoom(17.0)
                myLocationOverlay?.enableFollowLocation()
                binding.mapView.postDelayed({
                    myLocationOverlay?.disableFollowLocation()
                }, 2500)
            } catch (e: Exception) {
                Log.e(TAG, "center failed", e)
            }
        } else {
            getCurrentLocation { lat, lng ->
                try {
                    binding.mapView.controller.animateTo(GeoPoint(lat, lng))
                    binding.mapView.controller.setZoom(17.0)
                } catch (e: Exception) {
                    Log.e(TAG, "center fallback failed", e)
                }
            }
        }
    }

    /**
     * Lấy vị trí hiện tại – ưu tiên FusedLocation, fallback LocationManager (ổn định trên máy không có GMS đầy đủ).
     */
    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(onResult: (Double, Double) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            safeToast(getString(R.string.permission_required))
            requestLocationPermission()
            return
        }

        lifecycleScope.launch {
            // 1. Thử FusedLocationProvider (Google Play Services)
            try {
                val client = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                val cts = CancellationTokenSource()
                val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
                if (location != null) {
                    onResult(location.latitude, location.longitude)
                    return@launch
                }
                val last = client.lastLocation.await()
                if (last != null) {
                    onResult(last.latitude, last.longitude)
                    return@launch
                }
            } catch (e: Exception) {
                Log.w(TAG, "FusedLocation failed, fallback to LocationManager", e)
            }

            // 2. Fallback: LocationManager (hoạt động tốt trên máy OEM / không GMS)
            try {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                var best: Location? = null
                for (p in providers) {
                    if (lm.isProviderEnabled(p)) {
                        val loc = lm.getLastKnownLocation(p)
                        if (loc != null && (best == null || loc.accuracy < best!!.accuracy)) {
                            best = loc
                        }
                    }
                }
                if (best != null) {
                    onResult(best.latitude, best.longitude)
                    return@launch
                }

                // Request one update
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        onResult(location.latitude, location.longitude)
                        try { lm.removeUpdates(this) } catch (_: Exception) {}
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                for (p in providers) {
                    if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                        // Timeout 8s
                        binding.root.postDelayed({
                            try { lm.removeUpdates(listener) } catch (_: Exception) {}
                        }, 8000)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LocationManager failed", e)
                withContext(Dispatchers.Main) {
                    safeToast("Không lấy được vị trí. Bật GPS và thử lại.")
                }
            }
        }
    }

    private fun showAddLocationDialog(lat: Double, lng: Double) {
        try {
            val dialogBinding = DialogAddLocationBinding.inflate(layoutInflater)
            dialogBinding.etLatitude.setText(String.format(java.util.Locale.US, "%.6f", lat))
            dialogBinding.etLongitude.setText(String.format(java.util.Locale.US, "%.6f", lng))
            dialogBinding.etLocationName.setText(getString(R.string.current_location))

            val dialog = AlertDialog.Builder(this)
                .setView(dialogBinding.root)
                .setCancelable(true)
                .create()

            dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
            dialogBinding.btnSave.setOnClickListener {
                val name = dialogBinding.etLocationName.text.toString().trim()
                val latitude = dialogBinding.etLatitude.text.toString().trim().toDoubleOrNull()
                val longitude = dialogBinding.etLongitude.text.toString().trim().toDoubleOrNull()

                if (name.isEmpty()) {
                    dialogBinding.etLocationName.error = "Nhập tên vị trí"
                    return@setOnClickListener
                }
                if (latitude == null || longitude == null ||
                    latitude !in -90.0..90.0 || longitude !in -180.0..180.0
                ) {
                    safeToast("Tọa độ không hợp lệ")
                    return@setOnClickListener
                }
                saveLocation(name, latitude, longitude)
                dialog.dismiss()
            }
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "showAddLocationDialog failed", e)
        }
    }

    private fun saveLocation(name: String, lat: Double, lng: Double) {
        // Chuẩn hóa tọa độ (tránh lỗi format số)
        val safeLat = String.format(java.util.Locale.US, "%.7f", lat).toDouble()
        val safeLng = String.format(java.util.Locale.US, "%.7f", lng).toDouble()

        if (safeLat !in -90.0..90.0 || safeLng !in -180.0..180.0) {
            safeToast("Tọa độ không hợp lệ")
            return
        }

        lifecycleScope.launch {
            try {
                if (repo.currentUser == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Phiên đăng nhập hết hạn. Đăng xuất rồi đăng nhập lại.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val item = LocationItem(
                    name = name.ifBlank { "Vị trí" },
                    latitude = safeLat,
                    longitude = safeLng
                )

                val result = repo.saveLocation(item)
                result.onSuccess { id ->
                    Toast.makeText(
                        this@MainActivity,
                        "Đã lưu: $name ($safeLat, $safeLng)",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.i(TAG, "Saved id=$id")
                    if (isMapReady) {
                        // Marker sẽ tự hiện qua observeLocations; vẫn focus map
                        binding.mapView.controller.animateTo(GeoPoint(safeLat, safeLng))
                        binding.mapView.controller.setZoom(16.0)
                    }
                }.onFailure { err ->
                    val msg = err.message ?: "Lỗi không xác định"
                    Toast.makeText(this@MainActivity, "Lỗi lưu: $msg", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "save failed: $msg", err)
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveLocation exception", e)
                Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(item: LocationItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("Xóa \"${item.name}\"?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        repo.deleteLocation(item.id)
                    } catch (e: Exception) {
                        safeToast("Lỗi xóa: ${e.message}")
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleBottomSheet() {
        bottomSheetVisible = !bottomSheetVisible
        binding.bottomSheet.visibility = if (bottomSheetVisible) View.VISIBLE else View.GONE
    }

    private fun hideBottomSheet() {
        bottomSheetVisible = false
        binding.bottomSheet.visibility = View.GONE
    }

    private fun safeToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_list -> {
                toggleBottomSheet()
                true
            }
            R.id.action_logout -> {
                try {
                    repo.logout()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    Log.e(TAG, "logout failed", e)
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            if (::binding.isInitialized) binding.mapView.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "onResume map error", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (::binding.isInitialized) binding.mapView.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "onPause map error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            myLocationOverlay?.disableMyLocation()
            if (::binding.isInitialized) {
                binding.mapView.overlays.clear()
                binding.mapView.onDetach()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy cleanup error", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
