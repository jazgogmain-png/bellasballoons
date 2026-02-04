/**
 * BELLA'S BALLOONS - WAR ROOM: MADNESS EDITION (v2.0.1)
 * v2.0.1: Fixes unresolved 'shim' error.
 * Features: Berserker Mode, Weather Hazards, Hunter Ghosts, and Raid Lobby.
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
    private lateinit var balloonView: BalloonView
    private lateinit var previewView: PreviewView
    private var isCameraActive = false
    private val MY_UUID: UUID = UUID.fromString("8982842b-71a5-4811-92e3-0570643a603c")
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null

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

        balloonView.onAction = { action ->
            when (action) {
                "toggleCamera" -> toggleCamera()
                "hostBT" -> setupBluetooth(true)
                "joinBT" -> setupBluetooth(false)
                "setTimer" -> setBattleTimer()
                "childLock" -> forceChildLock()
                "pickPop" -> { currentSoundSlot = "pop"; launchSoundPicker() }
                "pickFart" -> { currentSoundSlot = "fart"; launchSoundPicker() }
                "togglePro" -> balloonView.toggleProMode()
            }
        }
    }

    override fun onPause() { super.onPause(); balloonView.pause() }
    override fun onResume() { super.onResume(); balloonView.resume() }

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

    private fun setupBluetooth(isHost: Boolean) {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 102); return
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
                val prefs = getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)
                sendPacket("PROFILE:${prefs.getString("username", "Lodi")}:${prefs.getInt("max_streak", 0)}")
                listenForPackets(btSocket?.inputStream)
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Connection Failed", Toast.LENGTH_SHORT).show() } }
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
            msg == "STINK" -> balloonView.triggerStinkCloud(1)
            msg == "STINK_TRIPLE" -> balloonView.triggerStinkCloud(3)
            msg.startsWith("PROFILE:") -> { val p = msg.split(":"); balloonView.setOpponent(p[1], p[2]) }
            msg.startsWith("TIMER:") -> { val s = msg.split(":")[1].toLong(); balloonView.startBattle(s) }
            msg == "POP" -> balloonView.flashOpponent()
        }
    }

    fun sendPacket(cmd: String) { Thread { try { outStream?.write(cmd.toByteArray()) } catch (e: Exception) {} }.start() }

    private fun launchSoundPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
        soundPickerLauncher.launch(intent)
    }

    private fun toggleCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101); return
        }
        if (!isCameraActive) startCamera() else stopCamera()
    }

    private fun startCamera() {
        val cpFuture = ProcessCameraProvider.getInstance(this)
        cpFuture.addListener({
            val cp = cpFuture.get()
            val pre = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try { cp.unbindAll(); cp.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pre); previewView.visibility = View.VISIBLE; balloonView.setCameraState(true); isCameraActive = true }
            catch (e: Exception) { Log.e("AR", "Fail", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() { ProcessCameraProvider.getInstance(this).get().unbindAll(); previewView.visibility = View.GONE; balloonView.setCameraState(false); isCameraActive = false }

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
        AlertDialog.Builder(this).setTitle("Battle Secs").setView(input)
            .setPositiveButton("START") { _, _ ->
                val s = input.text.toString().toLongOrNull() ?: 60L
                balloonView.startBattle(s); sendPacket("TIMER:$s")
            }.show()
    }
}

class BalloonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {
    var onAction: ((String) -> Unit)? = null
    private val balloons = CopyOnWriteArrayList<Balloon>()
    private val particles = CopyOnWriteArrayList<Particle>()
    private val activeStreams = ConcurrentHashMap<String, Int>()
    private val random = Random()
    private val prefs = context.getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)

    private var username = prefs.getString("username", "Warrior")
    private var maxStreak = prefs.getInt("max_streak", 0)
    private var currentStreak = 0; private var bpm = 0; private val popTimes = LinkedList<Long>()
    private var oppName = "Unknown"; private var oppBest = "0"; private var oppFlash = 0
    private var battleEndTime = 0L; private var isBattleActive = false

    // State & Powerups
    private var isProMode = false; private var spawnRadius = 180f; private var speedMultiplier = 1.0f
    private var berserkerTime = 0L; private var totalPops = 0
    private var windX = 0f; private var windTimer = 0

    // UI Helpers
    private val shim = 30f // RESTORED FOR THE HUD
    private var selectedAction = ""
    private val actionButtons = mutableListOf<MenuButton>()
    data class MenuButton(val id: String, val label: String, val l: Float, val t: Float, val r: Float, val b: Float)

    private var menuPressStartTime = 0L; private val MENU_LONG_PRESS_TIME = 2000L
    private val MAX_BALLOON_RADIUS = 550f
    private var soundPool: SoundPool; private var popId = -1; private var inflateId = -1; private var fartId = -1; private var bootFartId = -1
    private var showMenu = false; private var useCamera = false; private var rainbowHue = 0f

    private var splashProgress = 0f
    private var hasPlayedBootSound = false
    private var showManual = false
    private var showWinnerDialog = false
    private var finalScoreText = ""
    private var comboText = ""; private var comboTime = 0L; private var comboCount = 0; private var screenShake = 0f

    private val uiFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val panelPaint = Paint().apply { color = Color.BLACK; alpha = 160 }

    init {
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        soundPool = SoundPool.Builder().setMaxStreams(50).setAudioAttributes(attr).build()
        popId = soundPool.load(context, R.raw.pop_sound, 1); inflateId = soundPool.load(context, R.raw.inflate, 1); fartId = soundPool.load(context, R.raw.fart, 1); bootFartId = soundPool.load(context, R.raw.boot_fart, 1)
    }

    fun setOpponent(name: String, best: String) { this.oppName = name; this.oppBest = best; invalidate() }
    fun startBattle(secs: Long) { battleEndTime = System.currentTimeMillis() + (secs * 1000); isBattleActive = true; currentStreak = 0; invalidate() }
    fun flashOpponent() { oppFlash = 10; invalidate() }
    fun setCameraState(active: Boolean) { useCamera = active; invalidate() }

    fun triggerStinkCloud(count: Int) {
        for (j in 1..count) {
            for (i in 0..40) particles.add(Particle(width/2f + (random.nextFloat()*200-100), height/2f + (random.nextFloat()*200-100), random.nextFloat()*20-10, random.nextFloat()*20-10, Color.parseColor("#4CAF50"), 200, isStink = true))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (splashProgress < 100f) { drawSplash(canvas); splashProgress += 0.55f; if (!hasPlayedBootSound && splashProgress > 8f) { soundPool.play(bootFartId, 1f, 1f, 2, 0, 1f); hasPlayedBootSound = true }; invalidate(); return }

        val isBerserker = System.currentTimeMillis() < berserkerTime
        if (screenShake > 0) { canvas.translate(random.nextFloat() * screenShake - screenShake/2, random.nextFloat() * screenShake - screenShake/2); screenShake *= 0.9f }

        if (useCamera) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        else if (isBerserker) canvas.drawColor(Color.parseColor("#440000"))
        else canvas.drawColor(Color.parseColor("#E1F5FE"))

        if (windTimer > 0) {
            windTimer--; windX = if (windTimer > 50) 8f else 0f
            uiFill.textSize = 40f; uiFill.color = Color.RED; uiFill.textAlign = Paint.Align.CENTER
            canvas.drawText("WINDY GALE!", width/2f, 250f, uiFill)
        } else if (random.nextInt(1000) < 2) { windTimer = 200 }

        rainbowHue = (rainbowHue + 1.2f) % 360f
        uiFill.textSize = 100f; uiFill.alpha = 80; uiFill.textAlign = Paint.Align.CENTER
        uiFill.color = Color.HSVToColor(floatArrayOf(rainbowHue, 0.7f, 1f))
        canvas.drawText("Bella's Balloons", width/2f, height/2f, uiFill); uiFill.alpha = 255; uiFill.color = Color.WHITE

        if (width > 0 && balloons.size < (if(isBerserker) 8 else 5)) spawnBalloon()

        for (p in particles) {
            val pPaint = Paint().apply { color = if (p.isRainbow) Color.HSVToColor(floatArrayOf((rainbowHue + random.nextInt(100)) % 360f, 0.8f, 1f)) else p.color; alpha = p.alpha }
            canvas.drawCircle(p.x, p.y, if(p.isStink) 45f else 10f, pPaint)
            p.x += p.vx; p.y += p.vy; p.alpha -= 4; if (p.alpha <= 0) particles.remove(p)
        }

        for (b in balloons) {
            if (b.isGhost) { b.radius = 120f; b.x += (random.nextFloat()*10-5); b.y += (random.nextFloat()*10-5) }
            if (b.isInflating) {
                b.radius += 3.0f; val bl = if (isProMode) 350f else 550f
                if (b.radius > bl) { startGhostMode(b); continue }
            } else if (b.isFarting) {
                b.fartTimer++; b.radius -= 3.5f; b.vx = random.nextFloat()*60-30; b.vy = random.nextFloat()*60-30
                if (b.fartTimer % 4 == 0) (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(20, 255))
                if (b.fartTimer > 100 || b.radius < 90f) { popBalloon(b); continue }
            }
            val paint = Paint().apply {
                color = b.color
                if (b.isGhost) alpha = 100
                if (b.isGolden) color = Color.parseColor("#FFD700")
            }
            canvas.drawCircle(b.x, b.y, b.radius, paint)
            if (!b.isInflating) {
                b.x += (b.vx * speedMultiplier) + windX
                b.y += (b.vy * speedMultiplier)
            }
            if (b.x-b.radius<0 || b.x+b.radius>width) b.vx *= -1; if (b.y-b.radius<0 || b.y+b.radius>height) b.vy *= -1
        }

        if (System.currentTimeMillis() - comboTime < 1500) { uiFill.textSize = 80f; uiFill.color = Color.YELLOW; canvas.drawText(comboText, width/2f, height*0.3f, uiFill); uiFill.color = Color.WHITE }
        drawHUD(canvas)
        if (showMenu) drawTacticalMenu(canvas)
        if (showManual) drawVisualManual(canvas)
        if (showWinnerDialog) drawWinnerPopup(canvas)
        invalidate()
    }

    private fun drawSplash(canvas: Canvas) {
        canvas.drawColor(Color.BLACK); val center = width/2f; val middle = height/2f
        uiFill.textSize = 80f; uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.WHITE
        canvas.drawText("BELLA'S BALLOONS", center, middle - 50, uiFill)
        val barWidth = width * 0.6f; val paint = Paint().apply { color = Color.DKGRAY; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(center - barWidth/2, middle + 60, center + barWidth/2, middle + 60, paint)
        paint.color = Color.HSVToColor(floatArrayOf(rainbowHue, 0.8f, 1f))
        canvas.drawLine(center - barWidth/2, middle + 60, center - barWidth/2 + (barWidth * (splashProgress/100f)), middle + 60, paint)
        uiFill.textSize = 32f; canvas.drawText("SKOL! Final Gas Check...", center, middle + 140, uiFill)
    }

    private fun drawVisualManual(canvas: Canvas) {
        val paint = Paint().apply { color = Color.BLACK; alpha = 245 }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        uiFill.textSize = 60f; uiFill.color = Color.YELLOW; uiFill.textAlign = Paint.Align.CENTER
        canvas.drawText("WAR ROOM GUIDE", width/2f, height*0.1f, uiFill)
        uiFill.textSize = 30f; uiFill.textAlign = Paint.Align.LEFT; uiFill.color = Color.WHITE
        val steps = arrayOf(
            "🎈 INFLATE: Hold and release.",
            "🔥 BERSERKER: Pop GOLD balloons for TRIPLE stink.",
            "🌪️ WEATHER: Fight the wind gales!",
            "👻 GHOSTS: Over-inflation creates streak-stealers.",
            "⚔️ BT RAID: Settings > Pair. One HOST, others JOIN.",
            "🛡️ LOCK: Long-press Top-Left for menu."
        )
        steps.forEachIndexed { i, s -> canvas.drawText(s, width*0.1f, height*0.2f + (i * 90), uiFill) }
        val btnP = Paint().apply { color = Color.RED; alpha = 200 }
        canvas.drawRoundRect(width*0.35f, height*0.82f, width*0.65f, height*0.9f, 20f, 20f, btnP)
        uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.WHITE; canvas.drawText("SKOL!", width/2f, height*0.87f, uiFill)
    }

    private fun drawTacticalMenu(canvas: Canvas) {
        val paint = Paint().apply { color = Color.BLACK; alpha = 245 }
        canvas.drawRoundRect(width*0.1f, height*0.1f, width*0.9f, height*0.9f, 40f, 40f, paint)
        uiFill.textAlign = Paint.Align.CENTER; uiFill.textSize = 60f; uiFill.color = Color.WHITE; canvas.drawText("TACTICAL COMMAND", width/2f, height*0.18f, uiFill)
        paint.color = Color.RED; paint.alpha = 200; canvas.drawCircle(width*0.87f, height*0.13f, 40f, paint)
        uiFill.textSize = 40f; uiFill.color = Color.WHITE; canvas.drawText("X", width*0.87f, height*0.145f, uiFill)
        actionButtons.clear()
        addButton(canvas, "toggleCamera", if(useCamera) "AR: ON" else "AR: OFF", width*0.15f, height*0.25f, width*0.5f, height*0.33f)
        addButton(canvas, "togglePro", if(isProMode) "PRO: ON" else "PRO: OFF", width*0.15f, height*0.35f, width*0.5f, height*0.43f)
        addButton(canvas, "hostBT", "HOST", width*0.15f, height*0.45f, width*0.5f, height*0.53f)
        addButton(canvas, "pickPop", "POP FX", width*0.15f, height*0.55f, width*0.5f, height*0.63f)
        addButton(canvas, "showManual", "MANUAL", width*0.52f, height*0.25f, width*0.85f, height*0.33f)
        addButton(canvas, "setTimer", "TIMER", width*0.52f, height*0.35f, width*0.85f, height*0.43f)
        addButton(canvas, "joinBT", "JOIN", width*0.52f, height*0.45f, width*0.85f, height*0.53f)
        addButton(canvas, "pickFart", "FART FX", width*0.52f, height*0.55f, width*0.85f, height*0.63f)
        addButton(canvas, "childLock", "CHILD LOCK (PIN)", width*0.15f, height*0.65f, width*0.85f, height*0.73f)
        if (selectedAction != "") {
            paint.color = Color.HSVToColor(floatArrayOf(rainbowHue, 0.8f, 1f)); paint.alpha = 255; canvas.drawRoundRect(width*0.3f, height*0.78f, width*0.7f, height*0.88f, 20f, 20f, paint)
            uiFill.color = Color.BLACK; uiFill.textSize = 50f; canvas.drawText("SKOL!", width/2f, height*0.845f, uiFill)
        }
    }

    private fun addButton(canvas: Canvas, id: String, label: String, l: Float, t: Float, r: Float, b: Float) {
        val sel = selectedAction == id
        val p = Paint().apply { color = if (sel) Color.parseColor("#FFD700") else Color.WHITE; alpha = if (sel) 100 else 40 }
        canvas.drawRoundRect(l, t, r, b, 15f, 15f, p)
        if (sel) { p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.alpha = 255; canvas.drawRoundRect(l, t, r, b, 15f, 15f, p); p.style = Paint.Style.FILL }
        uiFill.textSize = 30f; uiFill.color = if (sel) Color.YELLOW else Color.WHITE; canvas.drawText(label, (l+r)/2f, (t+b)/2f + 10, uiFill)
        actionButtons.add(MenuButton(id, label, l, t, r, b))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked; val ai = event.actionIndex; val tx = event.getX(ai); val ty = event.getY(ai)
        if (splashProgress < 100f) return true
        if (showManual && action == MotionEvent.ACTION_DOWN) { if (tx > width*0.35f && tx < width*0.65f && ty > height*0.82f && ty < height*0.9f) showManual = false; return true }
        if (showWinnerDialog && action == MotionEvent.ACTION_DOWN) { if (tx > width*0.4f && tx < width*0.6f && ty > height*0.65f && ty < height*0.75f) { showWinnerDialog = false; currentStreak = 0 }; return true }
        if (showMenu && action == MotionEvent.ACTION_DOWN) {
            if (Math.hypot((tx - width*0.87f).toDouble(), (ty - height*0.13f).toDouble()) < 60) { showMenu = false; selectedAction = ""; return true }
            if (selectedAction != "" && tx > width*0.3f && tx < width*0.7f && ty > height*0.78f && ty < height*0.88f) {
                if (selectedAction == "showManual") { showManual = true; showMenu = false } else { onAction?.invoke(selectedAction) }
                showMenu = false; selectedAction = ""; return true
            }
            for (btn in actionButtons) if (tx > btn.l && tx < btn.r && ty > btn.t && ty < btn.b) { selectedAction = btn.id; invalidate(); return true }
            return true
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (tx < 250 && ty < 250) { menuPressStartTime = System.currentTimeMillis() } else {
                    for (b in balloons.asReversed()) if (!b.isInflating && Math.hypot((tx-b.x).toDouble(), (ty-b.y).toDouble()) < b.radius + 65) {
                        if (b.isGhost) { currentStreak = Math.max(0, currentStreak - 5); balloons.remove(b); invalidate(); return true }
                        b.isInflating = true; b.pointerId = event.getPointerId(ai); activeStreams[b.id] = soundPool.play(inflateId, 0.5f, 0.5f, 1, -1, 1f); return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> { if (tx < 250 && ty < 250 && menuPressStartTime != 0L) { if (System.currentTimeMillis() - menuPressStartTime > MENU_LONG_PRESS_TIME) { showMenu = true; menuPressStartTime = 0L; (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(100, 255)) } } else menuPressStartTime = 0L }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { menuPressStartTime = 0L; val pId = event.getPointerId(ai); for (b in balloons) if (b.isInflating && b.pointerId == pId) { b.isInflating = false; b.pointerId = -1; stopBalloonSound(b.id); if (b.radius > 280f) startFarting(b) else popBalloon(b); return true } }
        }
        return true
    }

    private fun drawWinnerPopup(canvas: Canvas) {
        val paint = Paint().apply { color = Color.BLACK; alpha = 230 }; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        uiFill.textSize = 80f; uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.YELLOW; canvas.drawText("BATTLE RESULTS", width/2f, height*0.35f, uiFill)
        uiFill.textSize = 50f; uiFill.color = Color.WHITE; canvas.drawText(finalScoreText, width/2f, height*0.5f, uiFill)
        val bp = Paint().apply { color = Color.HSVToColor(floatArrayOf(rainbowHue, 0.8f, 1f)) }; canvas.drawRoundRect(width*0.4f, height*0.65f, width*0.6f, height*0.75f, 20f, 20f, bp)
        uiFill.textSize = 40f; uiFill.color = Color.BLACK; canvas.drawText("SKOL!", width/2f, height*0.72f, uiFill)
    }

    private fun drawHUD(canvas: Canvas) {
        uiFill.textSize = 35f; uiFill.textAlign = Paint.Align.LEFT; canvas.drawRect(shim, shim, shim + 320, shim + 110, panelPaint)
        canvas.drawText("BPM: $bpm", shim + 20, shim + 50, uiFill); canvas.drawText("STREAK: $currentStreak", shim + 20, shim + 95, uiFill)
        if (isBattleActive) { val rem = (battleEndTime - System.currentTimeMillis()) / 1000; if (rem <= 0) { isBattleActive = false; triggerWinnerSequence() }
            canvas.drawRect(width - shim - 220, shim, width - shim, shim + 80, panelPaint); uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.YELLOW; canvas.drawText("${rem}s", width - shim - 110, shim + 55, uiFill); uiFill.color = Color.WHITE }
        canvas.drawRect(width - shim - 350, height - shim - 120, width - shim, height - shim, panelPaint)
        if (oppFlash > 0) { val p = Paint().apply { color = Color.GREEN; alpha = 100 }; canvas.drawRect(width-shim-350, height-shim-120, width-shim, height-shim, p); oppFlash-- }
        uiFill.textAlign = Paint.Align.RIGHT; canvas.drawText(oppName, width - shim - 20, height - shim - 70, uiFill); uiFill.textSize = 28f; canvas.drawText("OPP BEST: $oppBest", width - shim - 20, height - shim - 25, uiFill)
    }

    private fun stopBalloonSound(id: String) { activeStreams[id]?.let { soundPool.stop(it); activeStreams.remove(id) } }
    private fun triggerWinnerSequence() { finalScoreText = "Streak: $currentStreak | BPM: $bpm"; showWinnerDialog = true; invalidate() }

    private fun popBalloon(b: Balloon) {
        stopBalloonSound(b.id); val now = System.currentTimeMillis()
        if (now - comboTime < 300) comboCount++ else comboCount = 1
        comboTime = now; val rc = comboCount >= 3

        if (b.isGolden) { berserkerTime = now + 5000; screenShake = 50f; comboText = "BERSERKER UNLEASHED! 🔥" }
        else if (comboCount >= 2) { screenShake = comboCount * 15f; comboText = when(comboCount) { 2 -> "LODI POP! 🇵🇭" 3 -> "VIKING RAID! ⚔️" else -> "PAMBANSANG POPPER! 🔥" } }

        currentStreak++; totalPops++
        if (currentStreak > maxStreak) { maxStreak = currentStreak; prefs.edit().putInt("max_streak", maxStreak).apply() }
        popTimes.addLast(now); while (popTimes.isNotEmpty() && popTimes.first < now - 60000) popTimes.removeFirst()
        bpm = popTimes.size; (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(30, 150))
        soundPool.play(popId, 1f, 1f, 1, 0, 1f); balloons.remove(b)

        val isBerserker = now < berserkerTime
        (context as MainActivity).sendPacket(if(isBerserker) "STINK_TRIPLE" else "POP")
        for (i in 0..25) particles.add(Particle(b.x, b.y, random.nextFloat()*20-10, random.nextFloat()*20-10, b.color, isRainbow = rc))
    }

    private fun startGhostMode(b: Balloon) { stopBalloonSound(b.id); b.isGhost = true; b.isInflating = false; b.radius = 120f; b.color = Color.LTGRAY; b.vx = random.nextFloat()*20-10; b.vy = random.nextFloat()*20-10 }
    private fun startFarting(b: Balloon) { b.isInflating = false; stopBalloonSound(b.id); b.isFarting = true; activeStreams[b.id] = soundPool.play(fartId, 1f, 1f, 1, 0, 1f) }
    private fun spawnBalloon() {
        val colors = intArrayOf(Color.RED, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.GREEN)
        val golden = totalPops > 0 && totalPops % 10 == 0
        balloons.add(Balloon(x = 200f + random.nextFloat()*(width-400f), y = 200f + random.nextFloat()*(height-400f), vx = random.nextFloat()*10-5, vy = random.nextFloat()*10-5, color = colors.random(), radius = spawnRadius, isGolden = golden))
        if (golden) totalPops++
    }
    fun pause() { soundPool.autoPause() }
    fun resume() { soundPool.autoResume() }
    fun toggleProMode() { isProMode = !isProMode; if (isProMode) { spawnRadius = 110f; speedMultiplier = 1.6f } else { spawnRadius = 180f; speedMultiplier = 1.0f }; balloons.clear(); invalidate() }
    fun loadCustomSound(slot: String, uri: Uri) { val fd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return; if (slot == "pop") popId = soundPool.load(fd, 1) else if (slot == "fart") fartId = soundPool.load(fd, 1) }
    fun updateUser() { username = prefs.getString("username", "Warrior") }
}

data class Balloon(val id: String = UUID.randomUUID().toString(), var x: Float, var y: Float, var vx: Float, var vy: Float, var color: Int, var radius: Float = 180f, var isInflating: Boolean = false, var isFarting: Boolean = false, var fartTimer: Int = 0, var pointerId: Int = -1, var isGolden: Boolean = false, var isGhost: Boolean = false)
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var alpha: Int = 255, var isStink: Boolean = false, var isRainbow: Boolean = false)