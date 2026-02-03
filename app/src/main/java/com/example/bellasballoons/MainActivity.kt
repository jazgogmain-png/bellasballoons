package com.example.bellasballoons

import android.Manifest
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
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var balloonView: BalloonView
    private lateinit var previewView: PreviewView
    private var isCameraActive = false
    private val MY_UUID: UUID = UUID.fromString("8982842b-71a5-4811-92e3-0570643a603c")
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

    // The Permissions List required for AR and Bluetooth
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions Secured. Skol!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions Denied. Battle limited.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        balloonView = findViewById(R.id.balloon_view)

        checkUsername()
        requestGamePermissions()

        balloonView.onAction = { action ->
            when (action) {
                "toggleCamera" -> toggleCamera()
                "hostBT" -> setupBluetooth(true)
                "joinBT" -> setupBluetooth(false)
                "setTimer" -> setBattleTimer()
            }
        }

        window.setDecorFitsSystemWindows(false)
        window.decorView.post {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
    }

    private fun requestGamePermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkUsername() {
        val prefs = getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)
        if (!prefs.contains("username")) {
            val input = EditText(this)
            AlertDialog.Builder(this).setTitle("Warrior Name").setView(input)
                .setPositiveButton("SKOL!") { _, _ ->
                    val name = input.text.toString().ifEmpty { "Lodi" }
                    prefs.edit().putString("username", name).apply()
                    balloonView.updateUser()
                }.show()
        }
    }

    private fun setBattleTimer() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Set Battle (Seconds)").setView(input)
            .setPositiveButton("START") { _, _ ->
                val secs = input.text.toString().toLongOrNull() ?: 60L
                balloonView.startBattle(secs)
                sendPacket("TIMER:$secs")
            }.show()
    }

    private fun toggleCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }

        if (!isCameraActive) startCamera() else stopCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                previewView.visibility = View.VISIBLE
                balloonView.setCameraState(true)
                isCameraActive = true
            } catch (e: Exception) { Log.e("AR", "Fail", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) {}
        previewView.visibility = View.GONE
        balloonView.setCameraState(false)
        isCameraActive = false
    }

    private fun setupBluetooth(isHost: Boolean) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter ?: return

        // Explicit Check for Bluetooth Connect Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestGamePermissions()
            return
        }

        Thread {
            try {
                if (isHost) {
                    val serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord("FartWar", MY_UUID)
                    btSocket = serverSocket?.accept()
                } else {
                    val target = bluetoothAdapter?.bondedDevices?.firstOrNull()
                    btSocket = target?.createRfcommSocketToServiceRecord(MY_UUID)
                    btSocket?.connect()
                }
                outStream = btSocket?.outputStream

                val prefs = getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)
                val name = prefs.getString("username", "Lodi") ?: "Lodi"
                val best = prefs.getInt("max_streak", 0)
                sendPacket("PROFILE:$name:$best")

                listenForPackets(btSocket?.inputStream)
            } catch (e: SecurityException) {
                runOnUiThread { Toast.makeText(this, "Security Denied BT", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Pair Devices in Settings First!", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

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
            msg == "STINK" -> balloonView.triggerStinkCloud()
            msg.startsWith("PROFILE:") -> {
                val parts = msg.split(":")
                balloonView.setOpponent(parts[1], parts[2])
            }
            msg.startsWith("TIMER:") -> {
                val secs = msg.split(":")[1].toLong()
                balloonView.startBattle(secs)
            }
            msg == "POP" -> balloonView.flashOpponent()
        }
    }

    fun sendPacket(cmd: String) {
        Thread { try { outStream?.write(cmd.toByteArray()) } catch (e: Exception) {} }.start()
    }
}

// --- DATA CLASSES ---
data class Balloon(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var radius: Float = 180f, var isInflating: Boolean = false, var isFarting: Boolean = false, var fartTimer: Int = 0, var fartStreamId: Int = 0, var pointerId: Int = -1)
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var alpha: Int = 255, var isStink: Boolean = false, var isConfetti: Boolean = false, var isRocket: Boolean = false)

// --- VIEW ENGINE ---
class BalloonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {
    var onAction: ((String) -> Unit)? = null
    private val balloons = CopyOnWriteArrayList<Balloon>()
    private val particles = CopyOnWriteArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()
    private val prefs = context.getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)

    private var username = prefs.getString("username", "Warrior")
    private var maxStreak = prefs.getInt("max_streak", 0)
    private var currentStreak = 0; private var bpm = 0; private val popTimes = LinkedList<Long>()

    private var oppName = "Unknown"; private var oppBest = "0"; private var oppFlash = 0
    private var battleEndTime = 0L; private var isBattleActive = false

    private val shim = 30f
    private var soundPool: SoundPool; private var popId = -1; private var inflateId = -1; private var fartId = -1
    private var showMenu = false; private var useCamera = false

    private val uiFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val panelPaint = Paint().apply { color = Color.BLACK; alpha = 160 }

    init {
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        soundPool = SoundPool.Builder().setMaxStreams(50).setAudioAttributes(attr).build()
        popId = soundPool.load(context, R.raw.pop_sound, 1)
        inflateId = soundPool.load(context, R.raw.inflate, 1)
        fartId = soundPool.load(context, R.raw.fart, 1)
    }

    fun updateUser() { username = prefs.getString("username", "Warrior") }
    fun setCameraState(active: Boolean) { useCamera = active; invalidate() }
    fun setOpponent(name: String, best: String) { oppName = name; oppBest = best; invalidate() }
    fun flashOpponent() { oppFlash = 10 }
    fun startBattle(secs: Long) {
        battleEndTime = System.currentTimeMillis() + (secs * 1000)
        isBattleActive = true
    }

    fun triggerStinkCloud() {
        for (i in 0..50) particles.add(Particle(width/2f, height/2f, random.nextFloat()*20-10, random.nextFloat()*20-10, Color.parseColor("#4CAF50"), 200, isStink = true))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (useCamera) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        else canvas.drawColor(Color.parseColor("#E1F5FE"))

        uiFill.textSize = 40f

        canvas.drawRect(shim, shim, shim + 320, shim + 110, panelPaint)
        uiFill.textAlign = Paint.Align.LEFT
        canvas.drawText("BPM: $bpm", shim + 20, shim + 50, uiFill)
        canvas.drawText("STREAK: $currentStreak", shim + 20, shim + 95, uiFill)

        if (isBattleActive) {
            val remaining = (battleEndTime - System.currentTimeMillis()) / 1000
            if (remaining <= 0) { isBattleActive = false; triggerWinnerScreen() }
            canvas.drawRect(width - shim - 250, shim, width - shim, shim + 80, panelPaint)
            uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.YELLOW
            canvas.drawText("${remaining}s", width - shim - 125, shim + 55, uiFill)
            uiFill.color = Color.WHITE
        }

        canvas.drawRect(width - shim - 350, height - shim - 120, width - shim, height - shim, panelPaint)
        if (oppFlash > 0) { paint.color = Color.GREEN; paint.alpha = 100; canvas.drawRect(width-shim-350, height-shim-120, width-shim, height-shim, paint); oppFlash-- }
        uiFill.textAlign = Paint.Align.RIGHT
        canvas.drawText(oppName, width - shim - 20, height - shim - 70, uiFill)
        uiFill.textSize = 30f; canvas.drawText("OPP BEST: $oppBest", width - shim - 20, height - shim - 25, uiFill)

        if (balloons.size < 5) spawnBalloon()
        for (p in particles) {
            paint.color = p.color; paint.alpha = p.alpha; canvas.drawCircle(p.x, p.y, if(p.isStink) 40f else 10f, paint)
            p.x += p.vx; p.y += p.vy; p.alpha -= 4; if (p.alpha <= 0) particles.remove(p)
        }
        for (b in balloons) {
            if (b.isInflating) b.radius += 2.5f
            paint.color = b.color; canvas.drawCircle(b.x, b.y, b.radius, paint)
            b.x += b.vx; b.y += b.vy
            if (b.x-b.radius<0 || b.x+b.radius>width) b.vx *= -1
            if (b.y-b.radius<0 || b.y+b.radius>height) b.vy *= -1
        }

        if (showMenu) drawMenu(canvas)
        invalidate()
    }

    private fun triggerWinnerScreen() {
        (context as MainActivity).runOnUiThread {
            AlertDialog.Builder(context).setTitle("Battle Over!").setMessage("Final Streak: $currentStreak").show()
            currentStreak = 0
        }
    }

    private fun drawMenu(canvas: Canvas) {
        paint.color = Color.BLACK; paint.alpha = 220; canvas.drawRoundRect(width*0.2f, height*0.15f, width*0.8f, height*0.85f, 30f, 30f, paint)
        uiFill.textAlign = Paint.Align.CENTER; uiFill.textSize = 50f; canvas.drawText("WAR ROOM", width/2f, height*0.25f, uiFill)
        drawButton(canvas, width*0.3f, height*0.32f, width*0.7f, height*0.42f, "SET BATTLE TIMER")
        drawButton(canvas, width*0.3f, height*0.45f, width*0.48f, height*0.55f, "HOST")
        drawButton(canvas, width*0.52f, height*0.45f, width*0.7f, height*0.55f, "JOIN")
        drawButton(canvas, width*0.3f, height*0.58f, width*0.7f, height*0.68f, if(useCamera) "AR: ON" else "AR: OFF")
    }

    private fun drawButton(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String) {
        paint.color = Color.WHITE; paint.alpha = 40; canvas.drawRoundRect(l, t, r, b, 15f, 15f, paint)
        uiFill.textSize = 28f; canvas.drawText(label, (l+r)/2f, (t+b)/2f + 10, uiFill)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showMenu && event.action == MotionEvent.ACTION_DOWN) {
            val tx = event.x; val ty = event.y
            if (ty > height*0.32f && ty < height*0.42f) onAction?.invoke("setTimer")
            else if (ty > height*0.45f && ty < height*0.55f) { if (tx < width*0.48f) onAction?.invoke("hostBT") else onAction?.invoke("joinBT") }
            else if (ty > height*0.58f && ty < height*0.68f) onAction?.invoke("toggleCamera")
            showMenu = false; return true
        }
        if (event.action == MotionEvent.ACTION_DOWN) {
            if (event.x < 200 && event.y < 200) { showMenu = true; return true }
            for (b in balloons.asReversed()) {
                if (Math.hypot((event.x - b.x).toDouble(), (event.y - b.y).toDouble()) < b.radius + 50) {
                    popBalloon(b); return true
                }
            }
            currentStreak = 0
        }
        return true
    }

    private fun popBalloon(b: Balloon) {
        currentStreak++; if (currentStreak > maxStreak) { maxStreak = currentStreak; prefs.edit().putInt("max_streak", maxStreak).apply() }
        popTimes.addLast(System.currentTimeMillis()); while (popTimes.isNotEmpty() && popTimes.first < System.currentTimeMillis() - 60000) popTimes.removeFirst()
        bpm = popTimes.size; soundPool.play(popId, 1f, 1f, 1, 0, 1f); balloons.remove(b)
        (context as MainActivity).sendPacket("POP")
        for (i in 0..20) particles.add(Particle(b.x, b.y, random.nextFloat()*20-10, random.nextFloat()*20-10, b.color))
    }
    private fun spawnBalloon() {
        val colors = intArrayOf(Color.RED, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.GREEN)
        balloons.add(Balloon(200f + random.nextFloat()*(width-400f), 200f + random.nextFloat()*(height-400f), random.nextFloat()*10-5, random.nextFloat()*10-5, colors.random()))
    }
}