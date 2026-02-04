/**
 * BELLA'S BALLOONS - WAR ROOM EDITION
 * Version: 1.4 "The Viking-Bisaya Update"
 * * Features added in this version:
 * - v1.1: Multi-touch physics and Screen Pinning (Child Lock)
 * - v1.2: AR CameraX integration & Z-Index Layering
 * - v1.3: Bluetooth War Room (Stink Cloud packet exchange)
 * - v1.4: Sound Exorcism (Concurrent Stream Mapping) and Bella-Proofing (Burst Limits)
 */

package com.example.bellasballoons

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.*
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {
    // UI Elements
    private lateinit var balloonView: BalloonView
    private lateinit var previewView: PreviewView
    private var isCameraActive = false

    // Bluetooth Logic (The War Room engine)
    private val MY_UUID: UUID = UUID.fromString("8982842b-71a5-4811-92e3-0570643a603c")
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

    // v1.4: Soundboard Slot Tracker
    private var currentSoundSlot = ""
    private val soundPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> balloonView.loadCustomSound(currentSoundSlot, uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        balloonView = findViewById(R.id.balloon_view)

        checkUsername()
        applyImmersiveMode()

        // v1.1-1.4: The Action Dispatcher connects View buttons to Activity logic
        balloonView.onAction = { action ->
            when (action) {
                "toggleCamera" -> toggleCamera()
                "hostBT" -> setupBluetooth(true)
                "joinBT" -> setupBluetooth(false)
                "setTimer" -> setBattleTimer()
                "childLock" -> forceChildLock()
                "pickPop" -> { currentSoundSlot = "pop"; launchSoundPicker() }
                "pickFart" -> { currentSoundSlot = "fart"; launchSoundPicker() }
                "showHowTo" -> showHowToDialog()
            }
        }
    }

    /**
     * CHILD LOCK: Uses Android LockTask to 'pin' the app.
     * Requires "App Pinning" to be enabled in System Settings.
     */
    private fun forceChildLock() {
        try { 
            this.startLockTask()
            Toast.makeText(this, "LODI MODE: PINNED 🛡️", Toast.LENGTH_SHORT).show() 
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Pinning Failed")
                .setMessage("Enable 'App Pinning' in device Security Settings.")
                .setPositiveButton("SETTINGS") { _, _ -> 
                    startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) 
                }.show()
        }
    }

    /**
     * BLUETOOTH WAR ROOM: Standard RFCOMM implementation.
     * Host listens for a connection, Joiner finds first bonded device.
     */
    private fun setupBluetooth(isHost: Boolean) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter ?: return

        // v1.2: Check for modern Android BT Connect permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 102)
            return
        }

        Thread {
            try {
                if (isHost) {
                    val ss = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("FartWar", MY_UUID)
                    btSocket = ss?.accept()
                } else {
                    val target = bluetoothAdapter?.bondedDevices?.firstOrNull()
                    btSocket = target?.createRfcommSocketToServiceRecord(MY_UUID)
                    btSocket?.connect()
                }
                outStream = btSocket?.outputStream
                sendPacket("PROFILE:${getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE).getString("username", "Lodi")}")
                listenForPackets(btSocket?.inputStream)
            } catch (e: Exception) { 
                runOnUiThread { Toast.makeText(this, "War Room Connection Failed", Toast.LENGTH_SHORT).show() } 
            }
        }.start()
    }

    // Packet Listener: Runs in background, updates UI on main thread
    private fun listenForPackets(stream: InputStream?) {
        val buffer = ByteArray(1024)
        while (true) {
            try {
                val bytes = stream?.read(buffer) ?: -1
                if (bytes > 0) {
                    val msg = String(buffer, 0, bytes)
                    runOnUiThread { handlePacket(msg) }
                }
            } catch (e: Exception) { break }
        }
    }

    private fun handlePacket(msg: String) {
        when {
            msg == "POP" -> balloonView.flashOpponent()
            msg == "STINK" -> balloonView.triggerStinkCloud()
            msg.startsWith("TIMER:") -> balloonView.startBattle(msg.split(":")[1].toLong())
        }
    }

    fun sendPacket(cmd: String) { Thread { try { outStream?.write(cmd.toByteArray()) } catch (e: Exception) {} }.start() }

    // Standard CameraX Boilerplate
    private fun startCamera() {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val cp = cpFuture.get()
            val pre = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try { 
                cp.unbindAll()
                cp.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pre)
                previewView.visibility = View.VISIBLE
                balloonView.setCameraState(true)
                isCameraActive = true 
            } catch (e: Exception) { Log.e("AR", "Camera Fail", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() { 
        ProcessCameraProvider.getInstance(this).get().unbindAll()
        previewView.visibility = View.GONE
        balloonView.setCameraState(false)
        isCameraActive = false 
    }

    private fun applyImmersiveMode() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
    }

    private fun checkUsername() { /* SharedPreferences logic */ }
    private fun showHowToDialog() { /* Instructions UI */ }
    private fun launchSoundPicker() { /* Intent for audio files */ }
    private fun setBattleTimer() { /* Long input dialog */ }
}

// --- VIEW ENGINE ---
class BalloonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {
    // Thread-safe lists for concurrent drawing and modifying
    private val balloons = CopyOnWriteArrayList<Balloon>()
    private val particles = CopyOnWriteArrayList<Particle>()
    
    // v1.4: EXORCIST MAP: Ensures sounds stop even if the object is destroyed
    private val activeStreams = ConcurrentHashMap<String, Int>()
    
    // Game State
    private var currentStreak = 0; private var bpm = 0
    private var isBattleActive = false
    private var comboCount = 0; private var screenShake = 0f

    // v1.4: BELLA-PROOFING (The "Burst" Limit)
    private val MAX_BALLOON_RADIUS = 550f 

    private var soundPool: SoundPool; private var popId = -1; private var inflateId = -1; private var fartId = -1

    init {
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        soundPool = SoundPool.Builder().setMaxStreams(50).setAudioAttributes(attr).build()
        popId = soundPool.load(context, R.raw.pop_sound, 1)
        inflateId = soundPool.load(context, R.raw.inflate, 1)
        fartId = soundPool.load(context, R.raw.fart, 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // v1.4: Combo Screen Shake
        if (screenShake > 0) {
            canvas.translate((Math.random() * screenShake).toFloat() - screenShake/2, (Math.random() * screenShake).toFloat() - screenShake/2)
            screenShake *= 0.9f
        }

        // Logic for drawing background, balloons, and HUD
        // [Existing drawing loops with MAX_BALLOON_RADIUS check]
    }

    /**
     * v1.4: SOUND ASSASSIN
     * Kills a sound by its ID in the ConcurrentHashMap.
     */
    private fun stopBalloonSound(id: String) {
        activeStreams[id]?.let { soundPool.stop(it); activeStreams.remove(id) }
    }

    // [Existing onTouchEvent, popBalloon, and startFarting logic]
}

data class Balloon(val id: String = UUID.randomUUID().toString(), var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var radius: Float = 180f, var isInflating: Boolean = false, var isFarting: Boolean = false, var fartTimer: Int = 0, var pointerId: Int = -1)
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var alpha: Int = 255, var isStink: Boolean = false)
