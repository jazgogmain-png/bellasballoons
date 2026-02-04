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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var balloonView: BalloonView
    private lateinit var previewView: PreviewView
    private var isCameraActive = false
    private val MY_UUID: UUID = UUID.fromString("8982842b-71a5-4811-92e3-0570643a603c")
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview_view)
        balloonView = findViewById(R.id.balloon_view)

        checkUsername()
        applyImmersiveMode()

        balloonView.onAction = { action ->
            when (action) {
                "toggleCamera" -> toggleCamera()
                "hostBT" -> setupBluetooth(true)
                "joinBT" -> setupBluetooth(false)
                "setTimer" -> setBattleTimer()
                "childLock" -> forceChildLock()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        balloonView.pause()
    }

    override fun onResume() {
        super.onResume()
        balloonView.resume()
    }

    private fun applyImmersiveMode() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun forceChildLock() {
        try { this.startLockTask(); Toast.makeText(this, "LODI MODE: PINNED 🛡️", Toast.LENGTH_SHORT).show() }
        catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Pinning Failed").setMessage("Enable 'App Pinning' in Settings.")
                .setPositiveButton("SETTINGS") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }.show()
        }
    }

    private fun checkUsername() {
        val prefs = getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)
        if (!prefs.contains("username")) {
            val input = EditText(this)
            AlertDialog.Builder(this).setTitle("Warrior Name").setView(input)
                .setPositiveButton("SKOL!") { _, _ ->
                    prefs.edit().putString("username", input.text.toString().ifEmpty { "Lodi" }).apply()
                    balloonView.updateUser()
                }.show()
        }
    }

    private fun setBattleTimer() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Set Battle (Secs)").setView(input)
            .setPositiveButton("START") { _, _ ->
                val secs = input.text.toString().toLongOrNull() ?: 60L
                balloonView.startBattle(secs)
                sendPacket("TIMER:$secs")
            }.show()
    }

    private fun toggleCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
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

        // --- THE SURGICAL PERMISSION CHECK TO KILL THE RED ERROR ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 102)
                return
            }
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
                sendPacket("PROFILE:${prefs.getString("username", "Lodi")}:${prefs.getInt("max_streak", 0)}")
                listenForPackets(btSocket?.inputStream)
            } catch (e: SecurityException) {
                runOnUiThread { Toast.makeText(this, "Permission Denied for BT", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "BT Fail: Check Pairing", Toast.LENGTH_SHORT).show() }
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
            msg.startsWith("PROFILE:") -> { val parts = msg.split(":"); balloonView.setOpponent(parts[1], parts[2]) }
            msg.startsWith("TIMER:") -> { val secs = msg.split(":")[1].toLong(); balloonView.startBattle(secs) }
            msg == "POP" -> balloonView.flashOpponent()
        }
    }

    fun sendPacket(cmd: String) { Thread { try { outStream?.write(cmd.toByteArray()) } catch (e: Exception) {} }.start() }
}

// --- DATA CLASSES & VIEW ENGINE (KEEPING YOUR PERFECT GHOST-FREE CODE) ---
// [REST OF THE FILE REMAINS UNCHANGED]

data class Balloon(
    val id: String = UUID.randomUUID().toString(),
    var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int,
    var radius: Float = 180f, var isInflating: Boolean = false,
    var isFarting: Boolean = false, var fartTimer: Int = 0,
    var pointerId: Int = -1
)
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var alpha: Int = 255, var isStink: Boolean = false)

class BalloonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {
    var onAction: ((String) -> Unit)? = null
    private val balloons = CopyOnWriteArrayList<Balloon>()
    private val particles = CopyOnWriteArrayList<Particle>()
    private val activeStreams = ConcurrentHashMap<String, Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()
    private val prefs = context.getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)

    private var username = prefs.getString("username", "Warrior")
    private var maxStreak = prefs.getInt("max_streak", 0)
    private var currentStreak = 0; private var bpm = 0; private val popTimes = LinkedList<Long>()
    private var oppName = "Unknown"; private var oppBest = "0"; private var oppFlash = 0
    private var battleEndTime = 0L; private var isBattleActive = false

    private var comboCount = 0; private var comboTime = 0L; private var comboText = ""; private var screenShake = 0f
    private val shim = 30f
    private var soundPool: SoundPool; private var popId = -1; private var inflateId = -1; private var fartId = -1
    private var showMenu = false; private var useCamera = false; private var menuTaps = 0; private var rainbowHue = 0f

    private val uiFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val panelPaint = Paint().apply { color = Color.BLACK; alpha = 160 }

    init {
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        soundPool = SoundPool.Builder().setMaxStreams(50).setAudioAttributes(attr).build()
        popId = soundPool.load(context, R.raw.pop_sound, 1); inflateId = soundPool.load(context, R.raw.inflate, 1); fartId = soundPool.load(context, R.raw.fart, 1)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        soundPool.release()
    }

    fun pause() { soundPool.autoPause() }
    fun resume() { soundPool.autoResume() }

    fun updateUser() { username = prefs.getString("username", "Warrior") }
    fun setCameraState(active: Boolean) { useCamera = active; invalidate() }
    fun setOpponent(name: String, best: String) { oppName = name; oppBest = best; invalidate() }
    fun flashOpponent() { oppFlash = 10 }
    fun startBattle(secs: Long) { battleEndTime = System.currentTimeMillis() + (secs * 1000); isBattleActive = true }
    fun triggerStinkCloud() { for (i in 0..50) particles.add(Particle(width/2f, height/2f, random.nextFloat()*20-10, random.nextFloat()*20-10, Color.parseColor("#4CAF50"), 200, isStink = true)) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (screenShake > 0) {
            canvas.translate(random.nextFloat() * screenShake - screenShake/2, random.nextFloat() * screenShake - screenShake/2)
            screenShake *= 0.9f
        }

        if (useCamera) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) else canvas.drawColor(Color.parseColor("#E1F5FE"))

        rainbowHue = (rainbowHue + 1.2f) % 360f
        uiFill.textSize = 100f; uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.HSVToColor(floatArrayOf(rainbowHue, 0.7f, 1f)); uiFill.alpha = 80
        canvas.drawText("Bella's Balloons", width/2f, height/2f, uiFill); uiFill.alpha = 255

        if (width > 0 && balloons.size < 5) spawnBalloon()

        for (p in particles) {
            paint.color = p.color; paint.alpha = p.alpha; canvas.drawCircle(p.x, p.y, if(p.isStink) 40f else 10f, paint)
            p.x += p.vx; p.y += p.vy; p.alpha -= 4; if (p.alpha <= 0) particles.remove(p)
        }

        for (b in balloons) {
            if (b.isInflating) b.radius += 2.5f
            else if (b.isFarting) {
                b.fartTimer++; b.radius -= 3.5f; b.vx = random.nextFloat()*60-30; b.vy = random.nextFloat()*60-30
                if (b.fartTimer % 4 == 0) (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(20, 255))
                if (b.fartTimer > 100 || b.radius < 90f) { popBalloon(b); continue }
            }
            paint.color = b.color; canvas.drawCircle(b.x, b.y, b.radius, paint)
            if (!b.isInflating) { b.x += b.vx; b.y += b.vy }
            if (b.x-b.radius<0 || b.x+b.radius>width) b.vx *= -1; if (b.y-b.radius<0 || b.y+b.radius>height) b.vy *= -1
        }

        if (System.currentTimeMillis() - comboTime < 1500) {
            uiFill.textSize = 80f; uiFill.color = Color.YELLOW
            canvas.drawText(comboText, width/2f, height*0.3f, uiFill)
            uiFill.color = Color.WHITE
        }

        drawHUD(canvas)
        if (showMenu) drawMenu(canvas)
        invalidate()
    }

    private fun drawHUD(canvas: Canvas) {
        uiFill.textSize = 35f; uiFill.alpha = 255
        canvas.drawRect(shim, shim, shim + 320, shim + 110, panelPaint)
        uiFill.textAlign = Paint.Align.LEFT
        canvas.drawText("BPM: $bpm", shim + 20, shim + 50, uiFill)
        canvas.drawText("STREAK: $currentStreak", shim + 20, shim + 95, uiFill)

        if (isBattleActive) {
            val rem = (battleEndTime - System.currentTimeMillis()) / 1000
            if (rem <= 0) { isBattleActive = false; triggerWinnerScreen() }
            canvas.drawRect(width - shim - 250, shim, width - shim, shim + 80, panelPaint)
            uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.YELLOW
            canvas.drawText("${rem}s", width - shim - 125, shim + 55, uiFill); uiFill.color = Color.WHITE
        }

        canvas.drawRect(width - shim - 350, height - shim - 120, width - shim, height - shim, panelPaint)
        if (oppFlash > 0) { paint.color = Color.GREEN; paint.alpha = 100; canvas.drawRect(width-shim-350, height-shim-120, width-shim, height-shim, paint); oppFlash-- }
        uiFill.textAlign = Paint.Align.RIGHT
        canvas.drawText(oppName, width - shim - 20, height - shim - 70, uiFill)
        uiFill.textSize = 28f; canvas.drawText("OPP BEST: $oppBest", width - shim - 20, height - shim - 25, uiFill)
    }

    private fun drawMenu(canvas: Canvas) {
        paint.color = Color.BLACK; paint.alpha = 220; canvas.drawRoundRect(width*0.2f, height*0.15f, width*0.8f, height*0.85f, 30f, 30f, paint)
        uiFill.textAlign = Paint.Align.CENTER; uiFill.textSize = 50f; canvas.drawText("WAR ROOM", width/2f, height*0.25f, uiFill)
        drawButton(canvas, width*0.3f, height*0.32f, width*0.7f, height*0.42f, "SET TIMER")
        drawButton(canvas, width*0.3f, height*0.45f, width*0.48f, height*0.55f, "HOST")
        drawButton(canvas, width*0.52f, height*0.45f, width*0.7f, height*0.55f, "JOIN")
        drawButton(canvas, width*0.3f, height*0.58f, width*0.7f, height*0.68f, if(useCamera) "AR: ON" else "AR: OFF")
        drawButton(canvas, width*0.3f, height*0.71f, width*0.7f, height*0.81f, "CHILD LOCK (PIN SCREEN)")
    }

    private fun drawButton(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String) {
        paint.color = Color.WHITE; paint.alpha = 40; canvas.drawRoundRect(l, t, r, b, 15f, 15f, paint)
        uiFill.textSize = 28f; canvas.drawText(label, (l+r)/2f, (t+b)/2f + 10, uiFill)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        if (showMenu && action == MotionEvent.ACTION_DOWN) {
            val pX = event.getX(0); val pY = event.getY(0)
            if (pY > height*0.32f && pY < height*0.42f) onAction?.invoke("setTimer")
            else if (pY > height*0.45f && pY < height*0.55f) { if (pX < width/2f) onAction?.invoke("hostBT") else onAction?.invoke("joinBT") }
            else if (pY > height*0.58f && pY < height*0.68f) onAction?.invoke("toggleCamera")
            else if (pY > height*0.71f && pY < height*0.81f) onAction?.invoke("childLock")
            showMenu = false; return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pX = event.getX(actionIndex); val pY = event.getY(actionIndex)
                if (pX < 200 && pY < 200) { menuTaps++; if (menuTaps >= 3) { showMenu = true; menuTaps = 0 } }
                else {
                    var hit = false
                    for (b in balloons.asReversed()) {
                        if (!b.isInflating && Math.hypot((pX - b.x).toDouble(), (pY - b.y).toDouble()) < b.radius + 65) {
                            b.isInflating = true; b.pointerId = pointerId
                            val sid = soundPool.play(inflateId, 0.5f, 0.5f, 1, -1, 1f)
                            activeStreams[b.id] = sid
                            hit = true; break
                        }
                    }
                    if (!hit && !showMenu) { currentStreak = 0; (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(50, 80)) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                for (b in balloons) {
                    if (b.isInflating && b.pointerId == pointerId) {
                        b.isInflating = false; b.pointerId = -1
                        stopBalloonSound(b.id)
                        if (b.radius > 280f) startFarting(b) else popBalloon(b)
                        break
                    }
                }
            }
        }
        return true
    }

    private fun stopBalloonSound(balloonId: String) {
        activeStreams[balloonId]?.let { sid ->
            soundPool.stop(sid)
            activeStreams.remove(balloonId)
        }
    }

    private fun triggerWinnerScreen() { (context as MainActivity).runOnUiThread { AlertDialog.Builder(context).setTitle("Round Over!").setMessage("Final Streak: $currentStreak").show() } }

    private fun popBalloon(b: Balloon) {
        stopBalloonSound(b.id)
        val now = System.currentTimeMillis()
        if (now - comboTime < 300) comboCount++ else comboCount = 1
        comboTime = now

        if (comboCount >= 2) {
            screenShake = comboCount * 15f
            comboText = when(comboCount) {
                2 -> "LODI POP! 🇵🇭"
                3 -> "VIKING RAID! ⚔️"
                else -> "PAMBANSANG POPPER! 🔥"
            }
        }

        currentStreak++; if (currentStreak > maxStreak) { maxStreak = currentStreak; prefs.edit().putInt("max_streak", maxStreak).apply() }
        popTimes.addLast(now); while (popTimes.isNotEmpty() && popTimes.first < now - 60000) popTimes.removeFirst()
        bpm = popTimes.size

        soundPool.play(popId, 1f, 1f, 1, 0, 1f)
        balloons.remove(b)
        (context as MainActivity).sendPacket("POP")
        for (i in 0..20) particles.add(Particle(b.x, b.y, random.nextFloat()*20-10, random.nextFloat()*20-10, b.color))
    }

    private fun startFarting(b: Balloon) {
        b.isInflating = false
        stopBalloonSound(b.id)
        b.isFarting = true
        val sid = soundPool.play(fartId, 1f, 1f, 1, 0, 1f)
        activeStreams[b.id] = sid
    }

    private fun spawnBalloon() {
        val colors = intArrayOf(Color.RED, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.GREEN)
        balloons.add(Balloon(x = 200f + random.nextFloat()*(width-400f), y = 200f + random.nextFloat()*(height-400f), vx = random.nextFloat()*10-5, vy = random.nextFloat()*10-5, color = colors.random()))
    }
}