package com.example.desktoppet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.random.Random

/**
 * Foreground service that renders any number of draggable sprite pets on top of
 * everything. Each pet roams the whole screen in its own direction and speed,
 * bounces off the edges, faces its direction of travel (front / back / left /
 * right rows), pauses while dragged, and opens [MainActivity] when tapped.
 *
 * The set of visible pets is driven by [ACTION_SET_PETS] intents carrying a list
 * of pet ids (see [PetCatalog]); the service diffs that list against what is on
 * screen, adding and removing pets as needed.
 */
class PetOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** Live pets, keyed by pet id. */
    private val pets = LinkedHashMap<String, PetInstance>()

    /** Decoded sheets cached so re-adding a pet doesn't re-decode the bitmap. */
    private val sheetCache = HashMap<String, SpriteSheet?>()

    private var ticking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val ids = intent?.getStringArrayListExtra(EXTRA_PET_IDS) ?: arrayListOf()
        syncPets(ids)
        if (pets.isEmpty()) {
            stopSelf()
        } else {
            startTicking()
        }
        return START_STICKY
    }

    /** Adds pets in [ids] that aren't shown yet and removes ones no longer selected. */
    private fun syncPets(ids: List<String>) {
        val wanted = ids.toSet()

        // Remove deselected pets.
        val toRemove = pets.keys.filter { it !in wanted }
        toRemove.forEach { removePet(it) }

        // Add newly selected pets.
        for (id in ids) {
            if (pets.containsKey(id)) continue
            val def = PetCatalog.byId(this, id) ?: continue
            addPet(def)
        }
    }

    private fun addPet(def: PetDef) {
        val sheet = sheetCache.getOrPut(def.assetName) {
            SpriteSheet.load(this, def.assetName)
        }

        val heightDp = Random.nextInt(96, 136).toFloat()
        val frameMs = Random.nextLong(90L, 160L) // different animation cadence per pet
        val view = SpriteView(this).apply {
            targetHeightDp = heightDp
            setAnimation(
                sheet?.bitmap,
                sheet?.row(def.layout.sideRow) ?: emptyList(),
                frameMs,
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Random.nextInt(0, (screenWidth() - dpToPx(120f)).coerceAtLeast(1))
            y = Random.nextInt(dpToPx(48f), (screenHeight() - dpToPx(180f)).coerceAtLeast(dpToPx(49f)))
        }

        val speed = dpToPx(Random.nextDouble(0.9, 2.6).toFloat()).toFloat().coerceAtLeast(1.5f)
        val (vx, vy) = PetBehavior.walkVelocity(speed, def.layout.roamVertically)
        val instance = PetInstance(
            def = def,
            sheet = sheet,
            view = view,
            params = params,
            fx = params.x.toFloat(),
            fy = params.y.toFloat(),
            speed = speed,
            vx = vx,
            vy = vy,
            frameMs = frameMs,
        )
        instance.stateUntil = SystemClock.uptimeMillis() + PetBehavior.walkDurationMs()

        view.setOnTouchListener(makeTouchListener(instance))
        pets[def.id] = instance
        runCatching { windowManager.addView(view, params) }
    }

    private fun removePet(id: String) {
        val instance = pets.remove(id) ?: return
        runCatching { windowManager.removeView(instance.view) }
    }

    private fun removeAllPets() {
        pets.keys.toList().forEach { removePet(it) }
    }

    // ---- Movement loop (single ticker drives every pet) ----

    private val ticker = object : Runnable {
        override fun run() {
            step()
            if (ticking) handler.postDelayed(this, FRAME_MS)
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(ticker, FRAME_MS)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(ticker)
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        val w = screenWidth()
        val h = screenHeight()
        for (instance in pets.values) {
            if (instance.interacting) continue
            val view = instance.view
            if (view.width == 0 || view.height == 0) continue

            if (instance.emoting) {
                if (now >= instance.stateUntil) startWalk(instance, now)
                continue // stand still while emoting
            }

            // End of a walk segment: either play an emote or head off in a new direction.
            if (now >= instance.stateUntil) {
                if (instance.def.layout.emoteRow != null && PetBehavior.shouldEmote()) {
                    startEmote(instance, now)
                    continue
                }
                val (vx, vy) = PetBehavior.walkVelocity(instance.speed, instance.def.layout.roamVertically)
                instance.vx = vx
                instance.vy = vy
                instance.stateUntil = now + PetBehavior.walkDurationMs()
            }

            instance.fx += instance.vx
            instance.fy += instance.vy

            // Turn around at the edges (reflecting velocity makes the facing follow).
            val maxX = (w - view.width).coerceAtLeast(0)
            val maxY = (h - view.height).coerceAtLeast(0)
            if (instance.fx <= 0f) { instance.fx = 0f; instance.vx = abs(instance.vx) }
            if (instance.fx >= maxX) { instance.fx = maxX.toFloat(); instance.vx = -abs(instance.vx) }
            if (instance.fy <= 0f) { instance.fy = 0f; instance.vy = abs(instance.vy) }
            if (instance.fy >= maxY) { instance.fy = maxY.toFloat(); instance.vy = -abs(instance.vy) }

            applyFacing(instance)

            instance.params.x = instance.fx.toInt()
            instance.params.y = instance.fy.toInt()
            runCatching { windowManager.updateViewLayout(view, instance.params) }
        }
    }

    private fun startWalk(instance: PetInstance, now: Long) {
        instance.emoting = false
        val (vx, vy) = PetBehavior.walkVelocity(instance.speed, instance.def.layout.roamVertically)
        instance.vx = vx
        instance.vy = vy
        instance.stateUntil = now + PetBehavior.walkDurationMs()
        instance.currentRow = -1 // force the walk row to be reapplied
    }

    private fun startEmote(instance: PetInstance, now: Long) {
        val emoteRow = instance.def.layout.emoteRow ?: return
        val sheet = instance.sheet ?: return
        instance.emoting = true
        instance.vx = 0f
        instance.vy = 0f
        instance.stateUntil = now + PetBehavior.emoteDurationMs()
        instance.currentRow = emoteRow
        instance.view.flipX = false
        instance.view.setAnimation(sheet.bitmap, sheet.row(emoteRow), instance.frameMs)
    }

    /** Switches the sprite to the row that matches the pet's direction of travel. */
    private fun applyFacing(instance: PetInstance) {
        val (row, flip) = PetAnimations.rowAndFlip(instance.def.layout, instance.vx, instance.vy)
        instance.view.flipX = flip
        if (row != instance.currentRow) {
            instance.currentRow = row
            val sheet = instance.sheet ?: return
            instance.view.setAnimation(sheet.bitmap, sheet.row(row), instance.frameMs)
        }
    }

    // ---- Touch: drag to move, tap to open ----

    private fun makeTouchListener(instance: PetInstance): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val tapTimeout = ViewConfiguration.getTapTimeout().toLong()

        var initialX = 0
        var initialY = 0
        var startRawX = 0f
        var startRawY = 0f
        var downTime = 0L
        var dragging = false

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    instance.interacting = true
                    initialX = instance.params.x
                    initialY = instance.params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    downTime = SystemClock.uptimeMillis()
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        instance.fx = (initialX + dx)
                        instance.fy = (initialY + dy)
                        instance.params.x = instance.fx.toInt()
                        instance.params.y = instance.fy.toInt()
                        runCatching { windowManager.updateViewLayout(instance.view, instance.params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    if (!dragging && elapsed <= tapTimeout * 2) openApp()
                    instance.interacting = false
                    true
                }

                else -> false
            }
        }
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics,
        ).toInt()

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun startAsForeground() {
        val channelId = "pet_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Desktop Pet",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps your pets on screen" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Your pets are on screen")
            .setContentText("Tap a pet to open the app, or drag it around.")
            .setSmallIcon(R.drawable.ic_pet_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopTicking()
        removeAllPets()
        super.onDestroy()
    }

    /** Per-pet runtime state. */
    private class PetInstance(
        val def: PetDef,
        val sheet: SpriteSheet?,
        val view: SpriteView,
        val params: WindowManager.LayoutParams,
        var fx: Float,
        var fy: Float,
        val speed: Float,
        var vx: Float,
        var vy: Float,
        val frameMs: Long,
    ) {
        var interacting = false
        var emoting = false
        var currentRow = -1
        var stateUntil = 0L
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_MS = 16L
        const val ACTION_SET_PETS = "com.example.desktoppet.SET_PETS"
        const val EXTRA_PET_IDS = "pet_ids"

        /** Show exactly [ids] (adds/removes to match). Empty list stops the service. */
        fun setPets(context: Context, ids: List<String>) {
            val intent = Intent(context, PetOverlayService::class.java).apply {
                action = ACTION_SET_PETS
                putStringArrayListExtra(EXTRA_PET_IDS, ArrayList(ids))
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PetOverlayService::class.java))
        }
    }
}
