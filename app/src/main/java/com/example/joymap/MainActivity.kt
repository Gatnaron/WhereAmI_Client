package com.example.joymap

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.joymap.Models.Child
import com.example.joymap.Models.Requests.SafeZoneRequest
import com.example.joymap.Models.SafeZone
import com.example.joymap.Services.LocationUpdateService
import com.example.joymap.Services.ParentApiService
import com.example.joymap.databinding.ActivityMainBinding
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private lateinit var mapObjectCollection: MapObjectCollection
    private lateinit var parentId: String
    private lateinit var parentApiService: ParentApiService

    private var isCreatingZone = false
    private var currentZoneName = ""
    private var currentZoneRadius = 1000f
    private var currentPoint: Point? = null
    private var currentZoneColor: Int = 1

    private var selectedPointMarker: PlacemarkMapObject? = null

    private val safeZones = mutableListOf<SafeZone>()
    private val children = mutableListOf<Child>()

    private val childMarkers = mutableMapOf<String, PlacemarkMapObject>()

    private val updateInterval = 10000L // 15 секунд
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val safeZoneMarkers = mutableListOf<com.yandex.mapkit.map.MapObject>()


    val inputListener = object : InputListener {
        override fun onMapTap(map: com.yandex.mapkit.map.Map, point: Point) {
            handleMapTap(point)
        }

        override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: Point) {
            // Handle long tap ...
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setApiKey()
        MapKitFactory.initialize(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapView = binding.mapview
        mapObjectCollection = mapView.map.mapObjects
        parentApiService = ParentApiService(this)
        parentId = getUserID()

        startChildLocationUpdates()
        binding.devicesButton.setOnClickListener { loadChildrenFromServer() }
        binding.addSafeZoneButton.setOnClickListener {
            if (currentPoint == null) {
                Toast.makeText(this, "Сначала выберите точку на карте!", Toast.LENGTH_SHORT).show()
            } else {
                showSafeZoneDialog()
            }
        }

        mapView.map.addInputListener(inputListener)

        // Загружаем детей при старте
        loadChildrenFromServer { firstChildPoint ->
            if (firstChildPoint != null) {
                moveCamera(firstChildPoint)
            } else {
                val spbPoint = Point(59.9342802, 30.3350986) // Точка по умолчанию
                moveCamera(spbPoint)
            }
        }

        loadSafeZonesFromServer() // Загружаем зоны безопасности
    }

    private fun handleMapTap(point: Point) {
        Log.d("MapTap", "Map tapped at: ${point.latitude}, ${point.longitude}")
        try {
            // Удаляем предыдущий маркер, если он существует
            selectedPointMarker?.let {
                mapObjectCollection.remove(it)
                selectedPointMarker = null
            }

            // Добавляем новый маркер
            selectedPointMarker = mapObjectCollection.addPlacemark(
                point,
                ImageProvider.fromResource(this, android.R.drawable.presence_online)
            )

            // Сохраняем точку
            currentPoint = point

            Toast.makeText(this, "Точка выбрана: ${point.latitude}, ${point.longitude}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MapTapError", "Ошибка при обработке нажатия на карту: ${e.message}")
            Toast.makeText(this, "Ошибка добавления точки", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUserID(): String {
        val sharedPreferences: SharedPreferences =
            this.getSharedPreferences("ParentAppPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("parentGuid", "")?.replace("\"", "").orEmpty().also {
            Log.i("UserID", "parentId: $it")
        }
    }

    private fun setApiKey() {
        MapKitFactory.setApiKey(MAPKIT_API_KEY)
    }

    private fun showSafeZoneDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_safe_zone, null)
        val zoneNameInput = dialogView.findViewById<EditText>(R.id.zone_name)
        val zoneRadiusInput = dialogView.findViewById<EditText>(R.id.zone_radius)
        val zoneColorSpinner = dialogView.findViewById<Spinner>(R.id.zone_color)

        AlertDialog.Builder(this)
            .setTitle("Добавить зону")
            .setView(dialogView)
            .setPositiveButton("Создать") { _, _ ->
                val name = zoneNameInput.text.toString()
                val radius = zoneRadiusInput.text.toString().toFloatOrNull()
                val colorIndex = zoneColorSpinner.selectedItemPosition

                if (name.isNotEmpty() && radius != null) {
                    currentZoneName = name
                    currentZoneRadius = radius
                    currentZoneColor = when (colorIndex) {
                        0 -> Color.GREEN
                        1 -> Color.BLUE
                        2 -> Color.RED
                        else -> Color.BLUE
                    }

                    saveSafeZoneToServer() // Сохраняем зону
                } else {
                    Toast.makeText(this, "Заполните все поля!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Загрузка зон безопасности с сервера и их отрисовка на карте.
     */
    private fun loadSafeZonesFromServer() {
        if (parentId.isEmpty()) {
            Toast.makeText(this, "Ошибка: ID родителя отсутствует", Toast.LENGTH_SHORT).show()
            return
        }

        parentApiService.getUserData(parentId) { user, error ->
            runOnUiThread {
                if (user != null && !user.safeZones.isNullOrEmpty()) {
                    safeZones.clear()
                    safeZones.addAll(user.safeZones)
                    drawSafeZonesOnMap()
                } else {
                    Toast.makeText(this, "Зоны безопасности не найдены: ${error.orEmpty()}", Toast.LENGTH_SHORT).show()
                    Log.e("SafeZonesError", "Ошибка загрузки зон: ${error.orEmpty()}")
                }
            }
        }
    }


    /**
     * Отрисовка всех зон безопасности на карте.
     */
    private fun drawSafeZonesOnMap() {
        // Удаляем только объекты SafeZone, не затрагивая другие маркеры
        safeZoneMarkers.forEach { mapObjectCollection.remove(it) }
        safeZoneMarkers.clear()

        // Рисуем зоны на карте
        for (zone in safeZones) {
            val coordinates = zone.location.split(",").mapNotNull { it.toDoubleOrNull() }
            if (coordinates.size == 2) {
                val point = Point(coordinates[0], coordinates[1])
                val circle = Circle(point, zone.radius)

                // Определяем цвет зоны
                val color = when (zone.color) {
                    0 -> Color.GREEN
                    1 -> Color.BLUE
                    2 -> Color.RED
                    else -> Color.BLUE
                }

                val safeZone = mapObjectCollection.addCircle(
                    circle,
                    color,
                    2f,
                    color
                )
                safeZoneMarkers.add(safeZone) // Сохраняем ссылку на объект
            } else {
                Log.e("DrawZone", "Ошибка парсинга координат: ${zone.location}")
            }
        }
    }

    /**
     * Сохранение новой зоны безопасности на сервере и добавление её на карту.
     */
    private fun saveSafeZoneToServer() {
        val point = currentPoint ?: return

        val location = "${point.latitude},${point.longitude}"
        val zoneRequest = SafeZoneRequest(
            ownerId = parentId,
            name = currentZoneName,
            color = when (currentZoneColor) {
                Color.GREEN -> 0
                Color.BLUE -> 1
                Color.RED -> 2
                else -> 1
            },
            location = location,
            radius = currentZoneRadius
        )

        parentApiService.addSafeZone(zoneRequest.ownerId, zoneRequest) { response ->
            runOnUiThread {
                try {
                    if (response.contains("success", true)) {
                        val newZone = SafeZone(
                            id = "", // Важно: используйте уникальный ID, если доступен
                            name = zoneRequest.name,
                            color = zoneRequest.color,
                            location = zoneRequest.location,
                            radius = zoneRequest.radius,
                            ownerId = zoneRequest.ownerId
                        )
                        safeZones.add(newZone)
                        loadSafeZonesFromServer()
                        Toast.makeText(this, "Зона успешно добавлена!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Ошибка: $response", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("SaveSafeZone", "Ошибка добавления зоны: ${e.message}")
                    Toast.makeText(this, "Ошибка добавления зоны!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadChildrenFromServer(onFirstChildLoaded: ((Point?) -> Unit)? = null) {
        if (parentId.isEmpty()) {
            Toast.makeText(this, "Ошибка: ID родителя отсутствует", Toast.LENGTH_SHORT).show()
            onFirstChildLoaded?.invoke(null)
            return
        }

        parentApiService.getUserData(parentId) { user, error ->
            runOnUiThread {
                if (user != null && !user.children.isNullOrEmpty()) {
                    val childrenList = user.children
                    showChildrenList(childrenList.map { it.deviceId }, childrenList)

                    // Возвращаем точку первого ребёнка
                    val firstChild = childrenList.first()
                    val locationParts = firstChild.location.split(",")
                    if (locationParts.size == 2) {
                        val latitude = locationParts[0].toDoubleOrNull()
                        val longitude = locationParts[1].toDoubleOrNull()
                        if (latitude != null && longitude != null) {
                            onFirstChildLoaded?.invoke(Point(latitude, longitude))
                            return@runOnUiThread
                        }
                    }
                } else {
                    Toast.makeText(this, "Дети не найдены: ${error.orEmpty()}", Toast.LENGTH_SHORT).show()
                    Log.e("ServerError", "Ошибка получения данных: ${error.orEmpty()}")
                }
                onFirstChildLoaded?.invoke(null)
            }
        }
    }


    private fun moveCamera(point: Point) {
        mapView.map.move(
            CameraPosition(point, 14f, 0f, 0f), // Масштаб 14 для ближнего увеличения
            Animation(Animation.Type.SMOOTH, 2f),
            null
        )
    }

    private fun showChildrenList(childNames: List<String>, children: List<Child>) {
        if (children.isEmpty()) {
            Toast.makeText(this, "Дети не найдены!", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Выберите устройство")
        builder.setItems(childNames.toTypedArray()) { _, which ->
            if (which in children.indices) {
                val selectedChild = children[which]
                moveCameraToChildLocation(selectedChild)
            } else {
                Toast.makeText(this, "Устройство не найдено!", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Закрыть", null)
        builder.show()
    }


    private fun moveCameraToChildLocation(child: Child?) {
        if (child == null || child.location.isBlank()) {
            Toast.makeText(this, "Ошибка: местоположение отсутствует", Toast.LENGTH_SHORT).show()
            return
        }

        val locationParts = child.location.split(",")
        if (locationParts.size < 2) {
            Toast.makeText(this, "Ошибка: неверный формат местоположения", Toast.LENGTH_SHORT).show()
            return
        }

        val latitude = locationParts[0].toDoubleOrNull()
        val longitude = locationParts[1].toDoubleOrNull()

        if (latitude != null && longitude != null) {
            val point = Point(latitude, longitude)
            mapView.map.move(
                CameraPosition(point, 16.5f, 0f, 0f),
                Animation(Animation.Type.SMOOTH, 2f), null
            )
        } else {
            Toast.makeText(this, "Ошибка: неверные координаты", Toast.LENGTH_SHORT).show()
        }
    }


    /**
     * Запуск обновления местоположений детей.
     */
    private fun startChildLocationUpdates() {
        scope.launch {
            while (isActive) {
                updateChildLocations()
                delay(updateInterval)
            }
        }
    }

    /**
     * Обновление местоположений детей на карте.
     */
    private suspend fun updateChildLocations() = withContext(Dispatchers.IO) {
        parentApiService.getUserData(parentId) { user, error ->
            runOnUiThread {
                if (user != null && user.children.isNotEmpty()) {
                    user.children.forEach { child ->
                        updateChildMarker(child)
                    }
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка загрузки данных: ${error.orEmpty()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Обновление маркера ребенка на карте.
     */
    private fun updateChildMarker(child: Child) {
        val locationParts = child.location.split(",")
        if (locationParts.size < 2) return

        val latitude = locationParts[0].toDoubleOrNull()
        val longitude = locationParts[1].toDoubleOrNull()
        if (latitude != null && longitude != null) {
            val point = Point(latitude, longitude)

            if (childMarkers.containsKey(child.deviceId)) {
                // Перемещение существующего маркера
                childMarkers[child.deviceId]?.geometry = point
            } else {
                // Использование встроенного значка Android
                val marker = binding.mapview.map.mapObjects.addPlacemark(
                    point,
                    ImageProvider.fromResource(this, android.R.drawable.star_on)
                )
                childMarkers[child.deviceId] = marker
            }
        } else {
            Log.e("UpdateMarker", "Неверные координаты для ребенка: ${child.deviceId}")
        }
    }

    override fun onStart() {
        super.onStart()
        binding.mapview.onStart()
        MapKitFactory.getInstance().onStart()
    }

    override fun onStop() {
        binding.mapview.onStop()
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel() // Остановка корутин
        super.onDestroy()
    }

    companion object {
        const val MAPKIT_API_KEY = "6ed44a4b-6543-4064-bebd-3029ebe6a1b9"
    }
}
