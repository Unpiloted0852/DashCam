package com.example.dashcam

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Decides when a drive is over. Speed alone can't tell a red light from "parked and walked away",
 * so it combines:
 *  - walking: the phone's step detector counts steps after the car has stopped;
 *  - car power: the charger is unplugged while stopped (only if it charged during the drive);
 *  - car Bluetooth (optional): the chosen car disconnects while stopped;
 *  - fallback timer: N minutes below walking pace.
 * Nothing triggers until the car has actually driven, and driving again cancels any countdown.
 * Call [tick] every couple of seconds while recording.
 */
class DriveEndDetector(
    private val ctx: Context,
    private val settings: DashSettings,
    private val onEnd: (reason: String) -> Unit,
) : SensorEventListener {

    companion object {
        private const val MOVED_MPS = 2.2f          // ~5 mph: the car has really driven
        private const val DRIVING_MPS = 3.0f        // ~7 mph: faster than walking -> still driving
        private const val STILL_MPS = 2.0f          // ~4.5 mph: counts as parked for the timer (walking pace too)
        private const val STEP_WINDOW_MS = 30_000L
        private const val STEPS_TO_TRIGGER = 12
        private const val WALK_GRACE_MS = 10_000L
        private const val POWER_GRACE_MS = 30_000L
        private const val BT_GRACE_MS = 20_000L
        private const val UNPLUG_WINDOW_MS = 20_000L // unplugged just before stopping still counts
    }

    private var hasMoved = false
    private var slowSince = 0L
    private var stillSince = 0L
    private var chargedWhileDriving = false
    private var charging = false
    private var unpluggedAt = 0L
    private var btDisconnectedAt = 0L
    private val steps = ArrayDeque<Long>()

    private class Pending(val reason: String, val deadline: Long, val cancelWhen: () -> Boolean)
    private var pending: Pending? = null

    /** For the UI: why and how soon recording will stop, or null. */
    var countdown: Pair<String, Long>? = null; private set

    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private var started = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> {
                    charging = false
                    unpluggedAt = SystemClock.elapsedRealtime()
                    EventLog.log("End", "charger unplugged (moved=$hasMoved, chargedWhileDriving=$chargedWhileDriving)")
                }
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (dev.address != settings.endBluetoothAddress) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    btDisconnectedAt = SystemClock.elapsedRealtime()
                    EventLog.log("End", "car Bluetooth disconnected")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> btDisconnectedAt = 0L
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        hasMoved = false; slowSince = 0; stillSince = 0; chargedWhileDriving = false
        unpluggedAt = 0; btDisconnectedAt = 0; steps.clear(); pending = null; countdown = null
        val bm = ctx.getSystemService(BatteryManager::class.java)
        charging = bm?.isCharging == true
        ContextCompat.registerReceiver(ctx, powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (settings.endBluetoothAddress.isNotEmpty() && hasPerm(btPermission())) {
            ContextCompat.registerReceiver(ctx, btReceiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        val stepSensor = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (settings.endOnWalking && stepSensor != null && hasPerm(Manifest.permission.ACTIVITY_RECOGNITION)) {
            sensors.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else if (settings.endOnWalking) {
            EventLog.log("End", "walking detection unavailable (sensor=${stepSensor != null}, " +
                "permission=${hasPerm(Manifest.permission.ACTIVITY_RECOGNITION)})")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { ctx.unregisterReceiver(powerReceiver) }
        runCatching { ctx.unregisterReceiver(btReceiver) }
        sensors?.unregisterListener(this)
        countdown = null; pending = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        steps.addLast(SystemClock.elapsedRealtime())
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    fun tick() {
        val now = SystemClock.elapsedRealtime()
        // Fresh GPS speed; with no fix (garage, tunnel) fall back to the last known one.
        val speed: Float? = Telemetry.speedMps()
            ?: if (Telemetry.freshFix() == null) Telemetry.location?.takeIf { it.hasSpeed() }?.speed else null
        while (steps.isNotEmpty() && now - steps.first() > STEP_WINDOW_MS) steps.removeFirst()

        if (speed != null && speed > MOVED_MPS) hasMoved = true
        if (speed != null && speed > DRIVING_MPS) {
            // Driving: nothing ends the drive, and any countdown is cancelled.
            slowSince = 0; stillSince = 0
            if (charging) chargedWhileDriving = true
            if (pending != null) EventLog.log("End", "countdown (${pending!!.reason}) cancelled: driving again")
            pending = null; countdown = null
            steps.clear()
            return
        }
        if (charging && hasMoved) chargedWhileDriving = true
        if (!hasMoved) { countdown = null; return }
        if (slowSince == 0L) slowSince = now
        if (speed != null && speed < STILL_MPS) { if (stillSince == 0L) stillSince = now } else if (speed != null) stillSince = 0

        // Strong signals arm a short countdown.
        if (pending == null) {
            when {
                settings.endOnWalking && steps.size >= STEPS_TO_TRIGGER && now - slowSince > 5_000 ->
                    arm("walking detected", WALK_GRACE_MS) { false }   // only driving again cancels it
                settings.endOnUnplug && !charging && chargedWhileDriving && unpluggedAt != 0L &&
                    unpluggedAt >= slowSince - UNPLUG_WINDOW_MS ->
                    arm("car power off", POWER_GRACE_MS) { charging }
                btDisconnectedAt != 0L && btDisconnectedAt >= slowSince - UNPLUG_WINDOW_MS ->
                    arm("car Bluetooth disconnected", BT_GRACE_MS) { btDisconnectedAt == 0L }
            }
        }
        pending?.let { p ->
            if (p.cancelWhen()) {
                EventLog.log("End", "countdown (${p.reason}) cancelled")
                pending = null
            } else if (now >= p.deadline) {
                finish(p.reason); return
            }
        }

        // Fallback: N minutes parked (walking pace counts as parked).
        val limit = settings.autoStopMs
        val timerLeft = if (limit > 0 && stillSince != 0L) limit - (now - stillSince) else null
        if (timerLeft != null && timerLeft <= 0) { finish("parked ${limit / 60000} min"); return }

        val p = pending
        countdown = when {
            p != null -> p.reason to (p.deadline - now).coerceAtLeast(0)
            timerLeft != null -> "parked" to timerLeft
            else -> null
        }
    }

    private fun arm(reason: String, graceMs: Long, cancelWhen: () -> Boolean) {
        pending = Pending(reason, SystemClock.elapsedRealtime() + graceMs, cancelWhen)
        EventLog.log("End", "$reason — stopping in ${graceMs / 1000} s unless driving resumes")
    }

    private fun finish(reason: String) {
        countdown = null; pending = null
        EventLog.log("End", "drive ended: $reason")
        onEnd(reason)
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun btPermission() = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
                                 else Manifest.permission.BLUETOOTH
}
