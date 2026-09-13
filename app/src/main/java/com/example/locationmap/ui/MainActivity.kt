package com.example.locationmap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.locationmap.R
import com.example.locationmap.data.FirebaseRepository
import com.example.locationmap.databinding.ActivityMainBinding
import com.example.locationmap.databinding.DialogAddLocationBinding
import com.example.locationmap.model.LocationItem
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo = FirebaseRepository()
    private lateinit var adapter: LocationAdapter
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val markers = mutableMapOf<String, Marker>()
    private var bottomSheetVisible = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            enableMyLocation()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check auth first
        if (repo.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // ========== CRITICAL: osmdroid config BEFORE setContentView ==========
        initOsmdroid()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupMap()
        setupRecycler()
        setupButtons()
        observeLocations()
        requestLocationPermission()
    }

    /**
     * Cấu hình osmdroid đúng chuẩn cho Android 10+ / 12+ / 13+
     * - Dùng app-specific storage (không cần quyền WRITE_EXTERNAL)
     * - User-Agent hợp lệ để tile server không block
     */
    private fun initOsmdroid() {
        val ctx = applicationContext

        // 1. Load preferences
        Configuration.getInstance().load(
            ctx,
            PreferenceManager.getDefaultSharedPreferences(ctx)
        )

        // 2. User-Agent bắt buộc (tile server OSM block nếu thiếu hoặc quá ngắn)
        Configuration.getInstance().userAgentValue =
            "LocationMapApp/1.0 (Android; com.example.locationmap)"

        // 3. Dùng thư mục riêng của app → không cần permission storage trên Android 10+
        val basePath = File(ctx.getExternalFilesDir(null), "osmdroid")
        val tileCache = File(basePath, "tiles")
        if (!basePath.exists()) basePath.mkdirs()
        if (!tileCache.exists()) tileCache.mkdirs()

        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = tileCache

        // 4. Các tùy chọn ổn định trên máy thật
        Configuration.getInstance().isDebugMode = false
        Configuration.getInstance().isDebugTileProviders = false
        Configuration.getInstance().isDebugMapView = false
        Configuration.getInstance().tileDownloadThreads = 4
        Configuration.getInstance().tileFileSystemThreads = 4
    }

    private fun setupMap() {
        binding.mapView.apply {
            // Dùng MAPNIK (OpenStreetMap) – HTTPS
            setTileSource(TileSourceFactory.MAPNIK)

            // Zoom buttons + multi-touch
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)

            // Scale tiles theo DPI màn hình (quan trọng trên máy có DPI cao)
            isTilesScaledToDpi = true

            // Zoom & center mặc định (Hà Nội)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(21.0285, 105.8542))

            // Giới hạn zoom
            minZoomLevel = 5.0
            maxZoomLevel = 20.0
        }

        // Long-press để thêm vị trí
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { showAddLocationDialog(it.latitude, it.longitude) }
                return true
            }
        })
        binding.mapView.overlays.add(0, mapEventsOverlay)

        // Force refresh tiles
        binding.mapView.invalidate()
    }

    private fun setupRecycler() {
        adapter = LocationAdapter(
            onItemClick = { item ->
                binding.mapView.controller.animateTo(GeoPoint(item.latitude, item.longitude))
                binding.mapView.controller.setZoom(17.0)
                hideBottomSheet()
            },
            onDeleteClick = { item ->
                confirmDelete(item)
            }
        )
        binding.rvLocations.layoutManager = LinearLayoutManager(this)
        binding.rvLocations.adapter = adapter
    }

    private fun setupButtons() {
        binding.fabMyLocation.setOnClickListener {
            centerOnMyLocation()
        }

        binding.fabAddLocation.setOnClickListener {
            getCurrentLocation { lat, lng ->
                showAddLocationDialog(lat, lng)
            }
        }

        binding.tvHint.setOnClickListener {
            binding.tvHint.visibility = View.GONE
        }
    }

    private fun observeLocations() {
        lifecycleScope.launch {
            repo.observeLocations().collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                updateMarkers(list)
            }
        }
    }

    private fun updateMarkers(list: List<LocationItem>) {
        val currentIds = list.map { it.id }.toSet()
        markers.keys.filter { it !in currentIds }.forEach { id ->
            markers[id]?.let { binding.mapView.overlays.remove(it) }
            markers.remove(id)
        }

        list.forEach { item ->
            if (!markers.containsKey(item.id)) {
                val marker = Marker(binding.mapView).apply {
                    position = GeoPoint(item.latitude, item.longitude)
                    title = item.name
                    snippet = String.format("%.6f, %.6f", item.latitude, item.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker)
                }
                binding.mapView.overlays.add(marker)
                markers[item.id] = marker
            }
        }
        binding.mapView.invalidate()
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
        // Xóa overlay cũ nếu có
        myLocationOverlay?.let { binding.mapView.overlays.remove(it) }

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
            enableMyLocation()
            // Không bật follow liên tục để tránh map nhảy
            disableFollowLocation()
        }
        binding.mapView.overlays.add(myLocationOverlay)
        binding.mapView.invalidate()
    }

    private fun centerOnMyLocation() {
        val loc = myLocationOverlay?.myLocation
        if (loc != null) {
            binding.mapView.controller.animateTo(loc)
            binding.mapView.controller.setZoom(17.0)
            myLocationOverlay?.enableFollowLocation()
            // Tắt follow sau 3 giây
            binding.mapView.postDelayed({
                myLocationOverlay?.disableFollowLocation()
            }, 3000)
        } else {
            getCurrentLocation { lat, lng ->
                val point = GeoPoint(lat, lng)
                binding.mapView.controller.animateTo(point)
                binding.mapView.controller.setZoom(17.0)
            }
        }
    }

    private fun getCurrentLocation(onResult: (Double, Double) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
            requestLocationPermission()
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()

        lifecycleScope.launch {
            try {
                val location = client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
                if (location != null) {
                    onResult(location.latitude, location.longitude)
                } else {
                    val last = client.lastLocation.await()
                    if (last != null) {
                        onResult(last.latitude, last.longitude)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Không lấy được vị trí. Hãy bật GPS và thử lại.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(this@MainActivity, "Thiếu quyền vị trí", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi GPS: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddLocationDialog(lat: Double, lng: Double) {
        val dialogBinding = DialogAddLocationBinding.inflate(layoutInflater)
        dialogBinding.etLatitude.setText(String.format("%.6f", lat))
        dialogBinding.etLongitude.setText(String.format("%.6f", lng))
        dialogBinding.etLocationName.setText(getString(R.string.current_location))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnSave.setOnClickListener {
            val name = dialogBinding.etLocationName.text.toString().trim()
            val latStr = dialogBinding.etLatitude.text.toString().trim()
            val lngStr = dialogBinding.etLongitude.text.toString().trim()

            if (name.isEmpty()) {
                dialogBinding.etLocationName.error = "Nhập tên vị trí"
                return@setOnClickListener
            }
            val latitude = latStr.toDoubleOrNull()
            val longitude = lngStr.toDoubleOrNull()
            if (latitude == null || longitude == null) {
                Toast.makeText(this, "Tọa độ không hợp lệ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveLocation(name, latitude, longitude)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveLocation(name: String, lat: Double, lng: Double) {
        lifecycleScope.launch {
            val item = LocationItem(name = name, latitude = lat, longitude = lng)
            val result = repo.saveLocation(item)
            result.onSuccess {
                Toast.makeText(this@MainActivity, getString(R.string.location_saved), Toast.LENGTH_SHORT).show()
                binding.mapView.controller.animateTo(GeoPoint(lat, lng))
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    "Lỗi lưu: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmDelete(item: LocationItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("Xóa \"${item.name}\"?")
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    repo.deleteLocation(item.id)
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
                repo.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        myLocationOverlay?.disableMyLocation()
        binding.mapView.onDetach()
    }
}
