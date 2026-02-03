package com.example.bellasballoons

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class MainActivity : AppCompatActivity() {
    private lateinit var gameView: BalloonView
    private var currentSlot = ""

    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            when (currentSlot) {
                "bg" -> gameView.setBackgroundImage(uri)
                "pop" -> gameView.loadCustomSound("pop", uri)
                "fart" -> gameView.loadCustomSound("fart", uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.decorView.post {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            try { startLockTask() } catch (e: Exception) {}
        }
        gameView = BalloonView(this) { slot ->
            currentSlot = slot
            val intent = if (slot == "bg") Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            else Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
            pickerLauncher.launch(intent)
        }
        setContentView(gameView)
    }
}

data class Balloon(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var radius: Float = 180f, var isInflating: Boolean = false, var isFarting: Boolean = false, var fartTimer: Int = 0, var fartStreamId: Int = 0)
data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var alpha: Int = 255, var isStink: Boolean = false, var isConfetti: Boolean = false, var isRocket: Boolean = false)

class BalloonView(context: Context, val onPickFile: (String) -> Unit) : View(context) {
    private val balloons = CopyOnWriteArrayList<Balloon>()
    private val particles = CopyOnWriteArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val random = Random()
    private val prefs = context.getSharedPreferences("BellaPrefs", Context.MODE_PRIVATE)

    private var soundPool: SoundPool
    private var popId: Int = -1; private var inflateId: Int = -1; private var fartId: Int = -1; private var fireworksId: Int = -1
    private var inflateStreamId: Int = 0

    private var sessionTotal = 0
    private var allTimeTotal = prefs.getInt("total_pops", 0)
    private val popTimes = LinkedList<Long>()
    private var bpm = 0

    private var showMenu = false
    private var showReadme = false
    private var bgBitmap: Bitmap? = null
    private var bgOpacity = 0.5f
    private var menuTaps = 0
    private var eggTaps = 0
    private var lastEggTapTime = 0L
    private var partyTicks = 0
    private var rainbowHue = 0f
    private var marqueeX = 0f
    private val manifesto = "AI IS THE STEERING WHEEL, BUT YOU ARE THE PILOT. KEEP CODING! BELLA'S BALLOONS v4.5    "

    private val uiOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 10f; typeface = Typeface.DEFAULT_BOLD }
    private val uiFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }

    init {
        val attr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        soundPool = SoundPool.Builder().setMaxStreams(100).setAudioAttributes(attr).build()
        popId = soundPool.load(context, R.raw.pop_sound, 1); inflateId = soundPool.load(context, R.raw.inflate, 1)
        fartId = soundPool.load(context, R.raw.fart, 1); fireworksId = soundPool.load(context, R.raw.fireworks, 1)
    }

    fun setBackgroundImage(uri: Uri) { try { bgBitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri)) } catch(e:Exception){} }
    fun loadCustomSound(slot: String, uri: Uri) {
        val fd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return
        if (slot == "pop") popId = soundPool.load(fd, 1) else if (slot == "fart") fartId = soundPool.load(fd, 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bgBitmap?.let { bgPaint.alpha = (bgOpacity * 255).toInt(); canvas.drawBitmap(it, null, Rect(0, 0, width, height), bgPaint) }

        rainbowHue = (rainbowHue + 1.2f) % 360f
        uiFill.textSize = 140f; uiFill.textAlign = Paint.Align.CENTER; uiFill.color = Color.HSVToColor(floatArrayOf(rainbowHue, 0.7f, 1f)); uiFill.alpha = 95
        canvas.drawText("Bella's Balloons", width/2f, height/2f, uiFill)
        uiFill.color = Color.WHITE

        if (width > 0 && height > 0 && balloons.size < 5) spawnBalloon()

        for (p in particles) {
            paint.color = p.color; paint.alpha = p.alpha
            canvas.drawCircle(p.x, p.y, if(p.isStink) 45f else if(p.isRocket) 18f else 10f, paint)
            p.x += p.vx; p.y += p.vy
            if (p.isStink) { p.vy -= 0.02f; p.alpha -= 1 }
            else if (p.isRocket) {
                p.vy -= 0.45f
                if (random.nextInt(3) == 0) particles.add(Particle(p.x, p.y, 0f, 2f, Color.YELLOW, 160))
                if (p.y < height * 0.22f || p.alpha < 35) { triggerExplosion(p.x, p.y); particles.remove(p) }
            } else if (p.isConfetti) { p.vy += 0.35f; p.alpha -= 2 }
            else { p.alpha -= 10 }
            if (p.alpha <= 0) particles.remove(p)
        }

        for (b in balloons) {
            if (b.isInflating) { b.radius += 2.5f; if (b.radius > 500f) startFarting(b) }
            else if (b.isFarting) {
                b.fartTimer++; b.radius -= 3.5f; b.vx = random.nextFloat() * 60 - 30; b.vy = random.nextFloat() * 60 - 30
                if (random.nextInt(2) == 0) particles.add(Particle(b.x, b.y, 0f, 0f, Color.parseColor("#81C784"), 180, true))
                if (b.fartTimer % 4 == 0) (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(20, 255))
                if (b.fartTimer > 100 || b.radius < 90f) { popBalloon(b); continue }
            }
            paint.color = b.color; canvas.drawCircle(b.x, b.y, b.radius, paint)
            paint.color = Color.WHITE; paint.alpha = 150; canvas.drawCircle(b.x - b.radius/3, b.y - b.radius/3, b.radius/4, paint)
            if (!b.isInflating) { b.x += b.vx; b.y += b.vy }
            if (b.x - b.radius < 0 || b.x + b.radius > width) b.vx *= -1
            if (b.y - b.radius < 0 || b.y + b.radius > height) b.vy *= -1
        }

        uiFill.alpha = 255; uiFill.textAlign = Paint.Align.LEFT; uiFill.textSize = 45f; uiOutline.textSize = 45f
        val statsText = "Sess: $sessionTotal | Total: $allTimeTotal | BPM: $bpm"
        canvas.drawText(statsText, 60f, 100f, uiOutline); canvas.drawText(statsText, 60f, 100f, uiFill)

        if (partyTicks > 0) {
            marqueeX -= 10f; if (marqueeX < -uiFill.measureText(manifesto)) marqueeX = width.toFloat()
            uiFill.textSize = 70f; uiOutline.textSize = 70f
            canvas.drawText(manifesto, marqueeX, height - 130f, uiOutline); canvas.drawText(manifesto, marqueeX, height - 130f, uiFill)
            if (random.nextInt(10) == 0) launchRocket()
            partyTicks--
        }

        if (showMenu) drawSleekMenu(canvas)
        if (showReadme) drawReadme(canvas)
        invalidate()
    }

    private fun launchRocket() {
        particles.add(Particle(random.nextFloat() * width, height.toFloat(), random.nextFloat() * 6 - 3f, -18f, Color.WHITE, 255, isRocket = true))
        soundPool.play(fireworksId, 0.4f, 0.4f, 1, 0, 1.1f)
    }

    private fun triggerExplosion(x: Float, y: Float) {
        soundPool.play(fireworksId, 1f, 1f, 2, 0, 1f)
        val hue = random.nextFloat() * 360f
        for (i in 0..75) particles.add(Particle(x, y, random.nextFloat() * 36 - 18, random.nextFloat() * 36 - 18, Color.HSVToColor(floatArrayOf(hue, 0.8f, 1f)), 255, isConfetti = true))
    }

    private fun drawSleekMenu(canvas: Canvas) {
        paint.color = Color.BLACK; paint.alpha = 230
        val rect = RectF(width*0.1f, height*0.1f, width*0.9f, height*0.9f)
        canvas.drawRoundRect(rect, 40f, 40f, paint)

        uiFill.textAlign = Paint.Align.CENTER; uiFill.textSize = 70f
        canvas.drawText("PRO DASHBOARD", width/2f, height*0.25f, uiFill)

        // Buttons
        drawButton(canvas, width*0.2f, height*0.35f, width*0.45f, height*0.48f, "GALLERY")
        drawButton(canvas, width*0.55f, height*0.35f, width*0.8f, height*0.48f, "CUSTOM POP")
        drawButton(canvas, width*0.2f, height*0.52f, width*0.45f, height*0.65f, "CUSTOM FART")
        drawButton(canvas, width*0.55f, height*0.52f, width*0.8f, height*0.65f, "HELP / README")

        uiFill.textSize = 30f; canvas.drawText("--- Slide Bottom for Opacity ---", width/2f, height*0.72f, uiFill)

        // Reset Button
        paint.color = Color.RED; paint.alpha = 180
        canvas.drawRoundRect(width*0.35f, height*0.78f, width*0.65f, height*0.88f, 20f, 20f, paint)
        uiFill.color = Color.WHITE; uiFill.textSize = 35f
        canvas.drawText("RESET STATS", width/2f, height*0.845f, uiFill)
    }

    private fun drawButton(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String) {
        paint.color = Color.WHITE; paint.alpha = 40
        canvas.drawRoundRect(l, t, r, b, 20f, 20f, paint)
        uiFill.textSize = 35f; uiFill.color = Color.WHITE
        canvas.drawText(label, (l+r)/2f, (t+b)/2f + 12f, uiFill)
    }

    private fun drawReadme(canvas: Canvas) {
        paint.color = Color.BLACK; paint.alpha = 250
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        uiFill.textAlign = Paint.Align.CENTER; uiFill.textSize = 40f
        val lines = listOf("README: PRO SUITE", "------------------", "1. Triple-Tap 'Stats' to return to menu.", "2. Tap center 'B' 10x for Egg Madness.", "3. Slide Dashboard bottom for Opacity.", "4. Hold Balloon to inflate/fart.", "", "Tap anywhere to exit.")
        var startY = height * 0.2f
        for(line in lines) { canvas.drawText(line, width/2f, startY, uiFill); startY += 60f }
    }

    private fun popBalloon(b: Balloon) {
        sessionTotal++; allTimeTotal++; prefs.edit().putInt("total_pops", allTimeTotal).apply()
        popTimes.addLast(System.currentTimeMillis())
        while (popTimes.isNotEmpty() && popTimes.first < System.currentTimeMillis() - 60000) popTimes.removeFirst()
        bpm = popTimes.size
        if (inflateStreamId != 0) { soundPool.stop(inflateStreamId); inflateStreamId = 0 }
        if (b.fartStreamId != 0) { soundPool.stop(b.fartStreamId); b.fartStreamId = 0 }
        soundPool.play(popId, 1f, 1f, 1, 0, 1f); balloons.remove(b)
        for (i in 0..40) {
            val c = if (partyTicks > 0) Color.HSVToColor(floatArrayOf(random.nextFloat()*360, 1f, 1f)) else b.color
            particles.add(Particle(b.x, b.y, random.nextFloat()*35-17.5f, random.nextFloat()*35-17.5f, c, 255, isConfetti = true))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked; val tx = event.getX(); val ty = event.getY()

        if (showReadme) { if (action == MotionEvent.ACTION_DOWN) showReadme = false; return true }

        if (showMenu) {
            if (action == MotionEvent.ACTION_DOWN) {
                // Button Logic
                if (ty > height*0.35f && ty < height*0.48f) {
                    if (tx < width*0.45f) onPickFile("bg") else if (tx > width*0.55f) onPickFile("pop")
                } else if (ty > height*0.52f && ty < height*0.65f) {
                    if (tx < width*0.45f) onPickFile("fart") else if (tx > width*0.55f) showReadme = true
                } else if (ty > height*0.78f && ty < height*0.88f && tx > width*0.35f && tx < width*0.65f) {
                    sessionTotal = 0; allTimeTotal = 0; prefs.edit().putInt("total_pops", 0).apply(); bpm = 0; popTimes.clear()
                } else if (ty > height*0.68f && ty < height*0.78f) {
                    bgOpacity = ((tx - width*0.2f) / (width*0.6f)).coerceIn(0f, 1f)
                } else { showMenu = false; menuTaps = 0 }
            }
            return true
        }

        if (action == MotionEvent.ACTION_DOWN) {
            if (tx < 450 && ty < 150) {
                menuTaps++; if (menuTaps >= 3) showMenu = true
            } else {
                var hitBalloon = false
                for (b in balloons.asReversed()) {
                    if (Math.hypot((tx - b.x).toDouble(), (ty - b.y).toDouble()) < b.radius + 65) {
                        b.isInflating = true; inflateStreamId = soundPool.play(inflateId, 0.6f, 0.6f, 1, -1, 1f); hitBalloon = true; break
                    }
                }
                if (!hitBalloon && Math.abs(tx - width/2) < 280 && Math.abs(ty - height/2) < 280) {
                    val now = System.currentTimeMillis(); if (now - lastEggTapTime > 3000) eggTaps = 0
                    lastEggTapTime = now; eggTaps++; particles.add(Particle(tx, ty, 0f, -5f, Color.YELLOW, 200))
                    if (eggTaps >= 10) { partyTicks = 1500; marqueeX = width.toFloat(); launchRocket() }
                } else if (!hitBalloon) { eggTaps = 0; menuTaps = 0 }
            }
        }

        if (action == MotionEvent.ACTION_UP) {
            for (b in balloons) {
                if (b.isInflating) {
                    b.isInflating = false; if (inflateStreamId != 0) { soundPool.stop(inflateStreamId); inflateStreamId = 0 }
                    if (b.radius > 280f) startFarting(b) else popBalloon(b)
                }
            }
        }
        return true
    }

    private fun startFarting(b: Balloon) {
        b.isInflating = false; if (inflateStreamId != 0) { soundPool.stop(inflateStreamId); inflateStreamId = 0 }
        b.isFarting = true; b.fartStreamId = soundPool.play(fartId, 1f, 1f, 1, 0, 1f)
    }

    private fun spawnBalloon() {
        val colors = intArrayOf(Color.parseColor("#FFADAD"), Color.parseColor("#FFD6A5"), Color.parseColor("#FDFFB6"), Color.parseColor("#CAFFBF"), Color.parseColor("#9BF6FF"), Color.parseColor("#A0C4FF"), Color.parseColor("#BDB2FF"))
        balloons.add(Balloon(200f + random.nextFloat()*(width-400f), 200f + random.nextFloat()*(height-400f), random.nextFloat()*8-4, random.nextFloat()*8-4, colors[random.nextInt(colors.size)]))
    }
}