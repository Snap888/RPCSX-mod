package net.rpcsx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.navigation.AppNavHost
import net.rpcsx.utils.FileUtil
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GitHub
import net.rpcsx.utils.RpcsxUpdater
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var unregisterUsbEventListener: () -> Unit
    private var isShowingSetupDialog = false

    // ✅ Sixaxis: Сенсоры
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // ✅ Sixaxis: Данные сенсоров
    private var accelX: Float = 0f
    private var accelY: Float = 0f
    private var accelZ: Float = 0f
    private var gyroX: Float = 0f
    private var gyroY: Float = 0f
    private var gyroZ: Float = 0f

    // Лаунчер для выбора кастомного .so файла через системный файловый менеджер
    private val customVersionLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { installCustomVersion(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GeneralSettings.init(this)

        // ✅ Sixaxis: Инициализация сенсоров
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Проверка наличия сенсоров
        if (accelerometer == null || gyroscope == null) {
            Toast.makeText(
                this,
                "Device doesn't have required sensors for Sixaxis",
                Toast.LENGTH_LONG
            ).show()
        }

        // If the previous session was killed (OOM/SIGKILL/native crash), surface
        // the real reason - the core log cannot capture an uncatchable kill.
        net.rpcsx.utils.ExitReasonReporter.reportLastAbnormalExit(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!RPCSX.initialized) {
            Permission.PostNotifications.requestPermission(this)

            with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
                val channel = NotificationChannel(
                    "rpcsx-progress",
                    getString(R.string.installation_progress),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                createNotificationChannel(channel)
            }

            RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null).toString()
            if (!RPCSX.rootDirectory.endsWith("/")) {
                RPCSX.rootDirectory += "/"
            }

            // The native overlay code (save/message/OSK dialogs) loads PS-button
            // glyphs from <config>/Icons/ui/*.png. They ship as APK assets; extract
            // them on first run so dialogs render with proper button icons.
            val iconsDir = File(RPCSX.rootDirectory + "config", "Icons")
            if (!File(iconsDir, "ui").exists()) {
                FileUtil.extractAssetDir(applicationContext, "Icons", iconsDir)
            }

            lifecycleScope.launch {
                GameRepository.load()
            }

            FirmwareRepository.load()
            GitHub.initialize(this)

            var rpcsxLibrary = GeneralSettings["rpcsx_library"] as? String
            val rpcsxUpdateStatus = GeneralSettings["rpcsx_update_status"]
            val rpcsxPrevLibrary = GeneralSettings["rpcsx_prev_library"] as? String

            if (rpcsxLibrary != null) {
                if (rpcsxUpdateStatus == false && rpcsxPrevLibrary != null) {
                    GeneralSettings["rpcsx_library"] = rpcsxPrevLibrary
                    GeneralSettings["rpcsx_installed_arch"] = GeneralSettings["rpcsx_prev_installed_arch"]
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_bad_version"] = RpcsxUpdater.getFileVersion(File(rpcsxLibrary))
                    GeneralSettings.sync()
                    File(rpcsxLibrary).delete()
                    rpcsxLibrary = rpcsxPrevLibrary

                    AlertDialogQueue.showDialog(
                        getString(R.string.failed_to_update_rpcsx),
                        getString(R.string.failed_to_load_new_version)
                    )
                } else if (rpcsxUpdateStatus == null) {
                    GeneralSettings["rpcsx_update_status"] = false
                    GeneralSettings.sync()
                }

                RPCSX.openLibrary(rpcsxLibrary)
            }

            val nativeLibraryDir =
                packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibraryDir

            if (RPCSX.activeLibrary.value != null) {
                // Hand the core the app-private dir for secrets (rpcn.yml) BEFORE
                // initialize, so the very first RPCN config load resolves to internal
                // storage. No-ops gracefully on older core .so builds.
                RPCSX.instance.setRpcnConfigDir(applicationContext.filesDir.absolutePath)

                RPCSX.instance.initialize(
                    RPCSX.rootDirectory,
                    UserRepository.getUserFromSettings()
                )

                // Apply the device-adaptive compile-thread cap before any game can
                // boot, so low-RAM devices don't OOM during first-boot compilation.
                net.rpcsx.utils.CompileThreadPolicy.apply(applicationContext)

                // Android battery-saver (FIFO present + low SPU busy-wait); default on.
                net.rpcsx.utils.PowerPolicy.apply()

                // Experimental: bias heavy threads to the big CPU cluster (default off).
                runCatching {
                    RPCSX.instance.setCpuAffinityMode(GeneralSettings["cpu_affinity"] as? Boolean ?: false)
                }

                // Experimental: low-power WFE waiting (default off).
                runCatching {
                    RPCSX.instance.setWfeMode(GeneralSettings["wfe_mode"] as? Boolean ?: false)
                }

                // Smooth shaders (async interpreter) is dysfunctional on the current
                // backend (freezes), so the toggle is removed and the feature is forced
                // OFF here - overriding any value a previous build may have saved - until
                // the non-blocking preload re-land lands. Do not re-expose until fixed.
                runCatching {
                    RPCSX.instance.setSmoothShaders(false)
                }

                val gpuDriverPath = GeneralSettings["gpu_driver_path"] as? String
                val gpuDriverName = GeneralSettings["gpu_driver_name"] as? String
                if (gpuDriverPath != null && gpuDriverName != null) {
                    RPCSX.instance.setCustomDriver(gpuDriverPath, gpuDriverName, nativeLibraryDir)
                }

                lifecycleScope.launch {
                    UserRepository.load()
                }

                RPCSX.initialized = true

                thread {
                    RPCSX.instance.startMainThreadProcessor()
                }

                thread {
                    RPCSX.instance.processCompilationQueue()
                }

                GeneralSettings["rpcsx_update_status"] = true

                if (rpcsxPrevLibrary != null) {
                    if (rpcsxLibrary != rpcsxPrevLibrary) {
                        File(rpcsxPrevLibrary).delete()
                    }
                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings.sync()
                }
            } else {
                // RPCSX не установлен - показываем диалог первоначальной настройки
                showInitialSetupDialog()
            }

            val updateFile = File(RPCSX.rootDirectory + "cache", "rpcsx-${BuildConfig.Version}.apk")
            if (updateFile.exists()) {
                updateFile.delete()
            }
        }

        setContent {
            RPCSXTheme {
                AppNavHost()
            }
        }

        if (RPCSX.activeLibrary.value != null) {
            unregisterUsbEventListener = listenUsbEvents(this)
        } else {
            unregisterUsbEventListener = {}
        }
    }

    // ✅ Sixaxis: Регистрация сенсоров при возобновлении активности
    override fun onResume() {
        super.onResume()

        // Включаем сенсоры если Sixaxis активирован
        val sixaxisEnabled = GeneralSettings["sixaxis_enabled"] as? Boolean ?: false
        if (sixaxisEnabled) {
            accelerometer?.let {
                sensorManager.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
            gyroscope?.let {
                sensorManager.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
        }
    }

    // ✅ Sixaxis: Отмена регистрации сенсоров при паузе
    override fun onPause() {
        super.onPause()

        // Отменяем регистрацию сенсоров
        sensorManager.unregisterListener(this)
    }

    // ✅ Sixaxis: Обработка событий сенсоров
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val sixaxisEnabled = GeneralSettings["sixaxis_enabled"] as? Boolean ?: false
        if (!sixaxisEnabled) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Android акселерометр: m/s²
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
                sendMotionData()
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Android гироскоп: rad/s
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
                sendMotionData()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Не требуется
    }

    // ✅ Sixaxis: Отправка данных в эмулятор
    private fun sendMotionData() {
        val sensitivity = GeneralSettings["sixaxis_sensitivity"] as? Float ?: 1.0f

        // Загружаем калибровку
        val gyroOffX = GeneralSettings["gyro_offset_x"] as? Float ?: 0f
        val gyroOffY = GeneralSettings["gyro_offset_y"] as? Float ?: 0f
        val gyroOffZ = GeneralSettings["gyro_offset_z"] as? Float ?: 0f
        val accelOffX = GeneralSettings["accel_offset_x"] as? Float ?: 0f
        val accelOffY = GeneralSettings["accel_offset_y"] as? Float ?: 0f
        val accelOffZ = GeneralSettings["accel_offset_z"] as? Float ?: 0f

        // Применяем калибровку и чувствительность
        val calibratedAccelX = (accelX - accelOffX) * sensitivity
        val calibratedAccelY = (accelY - accelOffY) * sensitivity
        val calibratedAccelZ = (accelZ - accelOffZ) * sensitivity
        val calibratedGyroX = (gyroX - gyroOffX) * sensitivity
        val calibratedGyroY = (gyroY - gyroOffY) * sensitivity
        val calibratedGyroZ = (gyroZ - gyroOffZ) * sensitivity

        // Отправляем в эмулятор
        RPCSX.instance.setMotionData(
            calibratedAccelX, calibratedAccelY, calibratedAccelZ,
            calibratedGyroX, calibratedGyroY, calibratedGyroZ
        )
    }

    // ✅ Sixaxis: Калибровка сенсоров
    fun calibrateSensors() {
        // Сохраняем текущие значения как offset
        GeneralSettings.setValue("gyro_offset_x", gyroX)
        GeneralSettings.setValue("gyro_offset_y", gyroY)
        GeneralSettings.setValue("gyro_offset_z", gyroZ)
        GeneralSettings.setValue("accel_offset_x", accelX)
        GeneralSettings.setValue("accel_offset_y", accelY)
        GeneralSettings.setValue("accel_offset_z", accelZ - 9.81f) // Компенсируем гравитацию

        Toast.makeText(this, "Sensors calibrated!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
    }

    private fun showInitialSetupDialog() {
        if (isShowingSetupDialog) return
        isShowingSetupDialog = true

        AlertDialogQueue.showDialog(
            title = getString(R.string.missing_rpcsx_lib),
            message = getString(R.string.setup_rpcsx_message),
            confirmText = getString(R.string.download),
            dismissText = getString(R.string.install_custom_version),
            onConfirm = {
                isShowingSetupDialog = false
                lifecycleScope.launch {
                    downloadAndInstallRPCSX()
                }
            },
            onDismiss = {
                isShowingSetupDialog = false
                customVersionLauncher.launch("*/*")
            }
        )
    }

    private suspend fun downloadAndInstallRPCSX() {
        val cacheDir = File(RPCSX.rootDirectory + "cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        when (val file = RpcsxUpdater.downloadUpdate(cacheDir) { _, _ -> }) {
            null -> {
                AlertDialogQueue.showDialog(
                    getString(R.string.error),
                    getString(R.string.failed_to_download_rpcsx)
                )
                showInitialSetupDialog()
            }
            else -> {
                RpcsxUpdater.installUpdate(this@MainActivity, file, isCustom = false)
            }
        }
    }

    private fun installCustomVersion(uri: Uri) {
        lifecycleScope.launch {
            try {
                val cacheDir = File(RPCSX.rootDirectory + "cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val customFile = File(cacheDir, "custom_rpcsx_${System.currentTimeMillis()}.so")
                contentResolver.openInputStream(uri)?.use { input ->
                    customFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                RpcsxUpdater.installUpdate(this@MainActivity, customFile, isCustom = true)
            } catch (e: Exception) {
                AlertDialogQueue.showDialog(
                    getString(R.string.error),
                    getString(R.string.unexpected_error)
                )
                showInitialSetupDialog()
            }
        }
    }
}