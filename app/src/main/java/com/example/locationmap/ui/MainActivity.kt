package com.example.locationmap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

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

        // Check auth
        if (repo.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // osmdroid config
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupMap()
        setupRecycler()
        setupButtons()
        observeLocations()
        requestLocationPermission()
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            // Default to Hanoi center
            controller.setCenter(GeoPoint(21.0285, 105.8542))
        }

        // Long press to add location
        val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { showAddLocationDialog(it.latitude, it.longitude) }
                return true
            }
        })
        binding.mapView.overlays.add(mapEventsOverlay)
    }

    private fun setupRecycler() {
        adapter = LocationAdapter(
            onItemClick = { item ->
                // Move map to location
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
            // Get current location then open dialog
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

                // Update markers on map
                updateMarkers(list)
            }
        }
    }

    private fun updateMarkers(list: List<LocationItem>) {
        // Remove old markers that no longer exist
        val currentIds = list.map { it.id }.toSet()
        markers.keys.filter { it !in currentIds }.forEach { id ->
            markers[id]?.let { binding.mapView.overlays.remove(it) }
            markers.remove(id)
        }

        // Add / update markers
        list.forEach { item ->
            if (!markers.containsKey(item.id)) {
                val marker = Marker(binding.mapView).apply {
                    position = GeoPoint(item.latitude, item.longitude)
                    title = item.name
                    snippet = "${item.latitude}, ${item.longitude}"
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
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        binding.mapView.overlays.add(myLocationOverlay)
        binding.mapView.invalidate()
    }

    private fun centerOnMyLocation() {
        myLocationOverlay?.myLocation?.let {
            binding.mapView.controller.animateTo(it)
            binding.mapView.controller.setZoom(17.0)
        } ?: run {
            getCurrentLocation { lat, lng ->
                binding.mapView.controller.animateTo(GeoPoint(lat, lng))
                binding.mapView.controller.setZoom(17.0)
            }
        }
    }

    private fun getCurrentLocation(onResult: (Double, Double) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
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
                    // Fallback last known
                    val last = client.lastLocation.await()
                    if (last != null) {
                        onResult(last.latitude, last.longitude)
                    } else {
                        Toast.makeText(this@MainActivity, "Không lấy được vị trí", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
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
                // Move map to new location
                binding.mapView.controller.animateTo(GeoPoint(lat, lng))
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    "${getString(R.string.error_occurred)}: ${it.message}",
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
}
