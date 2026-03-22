/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.backtap

import android.hardware.SensorEvent
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

data class TapCandidate(
    val detectedUptimeMs: Long,
    val amplitude: Float,
    val durationMs: Float,
    val accelEnergy: Float,
    val gyroEnergy: Float,
    val accelToGyroRatio: Float,
    val decayRatio: Float,
    val gravityShift: Float,
    val score: Float
)

private data class PendingTap(
    val detectedNs: Long,
    val detectedUptimeMs: Long,
    val sampleIndex: Long,
    val amplitude: Float,
    val durationSamples: Int,
    val accelEnergy: Float,
    val gyroEnergy: Float,
    val accelToGyroRatio: Float,
    val gravityShift: Float
)

private data class TapFeatures(
    val amplitude: Float,
    val durationMs: Float,
    val accelEnergy: Float,
    val accelToGyroRatio: Float,
    val decayRatio: Float,
    val gravityShift: Float
)

class TapDetectionEngine(
    private val onTapDetected: (TapCandidate) -> Unit
) {
    companion object {
        private const val RESAMPLE_INTERVAL_NS = 2_400_000L
        private const val HISTORY_SIZE = 96
        private const val PRE_ENERGY_SAMPLES = 5L
        private const val POST_ENERGY_SAMPLES = 6L
        private const val COOLDOWN_NS = 100_000_000L
        private const val EPSILON = 0.001f
        private const val DEFAULT_NOISE_FLOOR = 0.03f
        private const val NOISE_ALPHA = 0.03f
        private const val HPF_PARA = 0.05f
        private const val GRAVITY_ALPHA = 0.88f
    }

    private val resampler = Resampler(intervalNs = RESAMPLE_INTERVAL_NS)
    private var hpfLastIn = 0f
    private var hpfLastOut = 0f

    private var prevZ = 0f
    private var zInitialized = false

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravityInitialized = false
    private var gravityShiftEma = 0f

    val positivePeakDetector = PeakDetector(windowSize = 64)
    val negativePeakDetector = PeakDetector(windowSize = 64)
    
    private val signalEnergyHistory = FloatArray(HISTORY_SIZE)
    private val gravityShiftHistory = FloatArray(HISTORY_SIZE)
    private val sampleIdHistory = LongArray(HISTORY_SIZE)
    private var sampleCounter = 0L

    private val gyroEnergyHistory = FloatArray(24)
    private var gyroHistoryHead = 0
    private var gyroHistoryCount = 0
    private var gyroEnergySq = 0f
    private var gyroAvailable = false

    private var noiseFloor = DEFAULT_NOISE_FLOOR
    private var currentThreshold = DEFAULT_NOISE_FLOOR
    var sensitivityMultiplier = 2.2f
        private set

    private var lastTapNs = 0L
    private var pendingTap: PendingTap? = null

    fun setGyroscopeAvailable(available: Boolean) {
        gyroAvailable = available
    }

    fun updateSensitivity(level: Int) { 
        sensitivityMultiplier = when (level) {
            0 -> 3.4f
            2 -> 1.35f
            else -> 2.2f
        }
    }

    fun processSensorEvent(event: SensorEvent) {
        resampler.update(event.values[0], event.values[1], event.values[2], event.timestamp) { sample ->
            processResampledSample(sample)
        }
    }

    fun processGyroEvent(event: SensorEvent) {
        if (!gyroAvailable) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val wSq = x * x + y * y + z * z
        gyroEnergySq = 0.88f * gyroEnergySq + 0.12f * wSq

        gyroEnergyHistory[gyroHistoryHead] = gyroEnergySq
        gyroHistoryHead = (gyroHistoryHead + 1) % gyroEnergyHistory.size
        gyroHistoryCount = minOf(gyroHistoryCount + 1, gyroEnergyHistory.size)
    }

    private fun processResampledSample(s: Resampler.Sample) {
        val gravityShift = updateGravityEstimate(s.x, s.y, s.z)

        val linZ = s.z - gravityZ

        if (!zInitialized) {
            prevZ = linZ
            zInitialized = true
            return
        }

        val dz = linZ - prevZ
        prevZ = linZ

        val hpfOut = HPF_PARA * (hpfLastOut + dz - hpfLastIn)
        hpfLastIn = dz
        hpfLastOut = hpfOut

        val sampleIndex = pushHistory(
            signalEnergy = hpfOut * hpfOut,
            gravityShift = gravityShift
        )

        updateNoiseFloor(abs(hpfOut))
        currentThreshold = (noiseFloor * sensitivityMultiplier).coerceIn(0.002f, 0.22f)

        positivePeakDetector.minNoiseTolerate = currentThreshold
        negativePeakDetector.minNoiseTolerate = currentThreshold * 0.6f

        positivePeakDetector.update(hpfOut)
        negativePeakDetector.update(-hpfOut)

        maybeFinalizePendingTap(sampleIndex)
        recognizeTapCandidate(s.tNs, sampleIndex)
    }

    private fun updateNoiseFloor(absHpfOut: Float) {
        val calmGate = (noiseFloor * sensitivityMultiplier).coerceIn(0.002f, 0.22f)
        if (absHpfOut < calmGate * 0.5f) {
            val updated = (1f - NOISE_ALPHA) * noiseFloor + NOISE_ALPHA * absHpfOut
            noiseFloor = updated.coerceIn(0.003f, 0.08f)
        }
    }

    private fun updateGravityEstimate(x: Float, y: Float, z: Float): Float {
        if (!gravityInitialized) {
            gravityX = x
            gravityY = y
            gravityZ = z
            gravityInitialized = true
            return 0f
        }

        val prevX = gravityX
        val prevY = gravityY
        val prevZ = gravityZ

        gravityX = GRAVITY_ALPHA * gravityX + (1f - GRAVITY_ALPHA) * x
        gravityY = GRAVITY_ALPHA * gravityY + (1f - GRAVITY_ALPHA) * y
        gravityZ = GRAVITY_ALPHA * gravityZ + (1f - GRAVITY_ALPHA) * z

        val dx = gravityX - prevX
        val dy = gravityY - prevY
        val dz = gravityZ - prevZ
        val delta = sqrt(dx * dx + dy * dy + dz * dz)

        gravityShiftEma = 0.80f * gravityShiftEma + 0.20f * delta
        return gravityShiftEma
    }

    private fun recognizeTapCandidate(tNs: Long, sampleIndex: Long) {
        if (pendingTap != null) return
        if (tNs - lastTapNs < COOLDOWN_NS) return

        val posId = positivePeakDetector.peakId
        val negId = negativePeakDetector.peakId
        val olderPeakId = minOf(posId, negId)
        val newerPeakId = maxOf(posId, negId)

        if (olderPeakId !in 3..6) return

        val durationSamples = newerPeakId - olderPeakId
        if (durationSamples !in 2..5) return

        val amplitude = maxOf(positivePeakDetector.amplitude, negativePeakDetector.amplitude)
        if (amplitude < currentThreshold * 1.15f) return

        val accelEnergy = averageHistory(
            signalEnergyHistory,
            sampleIndex - PRE_ENERGY_SAMPLES + 1,
            sampleIndex
        )
        if (accelEnergy < currentThreshold * currentThreshold * 0.7f) return

        val gyroEnergy = averageRecentGyroEnergy()
        val accelToGyroRatio = if (gyroAvailable) {
            (accelEnergy / (gyroEnergy + 0.05f)).coerceAtMost(8.0f)
        } else {
            1.5f
        }

        pendingTap = PendingTap(
            detectedNs = tNs,
            detectedUptimeMs = SystemClock.uptimeMillis(),
            sampleIndex = sampleIndex,
            amplitude = amplitude,
            durationSamples = durationSamples,
            accelEnergy = accelEnergy,
            gyroEnergy = gyroEnergy,
            accelToGyroRatio = accelToGyroRatio,
            gravityShift = averageHistory(
                gravityShiftHistory,
                sampleIndex - 2,
                sampleIndex
            )
        )
    }

    private fun maybeFinalizePendingTap(currentSampleIndex: Long) {
        val pending = pendingTap ?: return
        if (currentSampleIndex - pending.sampleIndex < POST_ENERGY_SAMPLES) return

        pendingTap = null

        val postEnergy = averageHistory(
            signalEnergyHistory,
            pending.sampleIndex + 1,
            pending.sampleIndex + POST_ENERGY_SAMPLES
        )
        val decayRatio = (postEnergy / (pending.accelEnergy + EPSILON)).coerceAtMost(4.0f)

        if (pending.gravityShift > 1.20f) return
        if (decayRatio > 0.85f) return
        if (gyroAvailable && pending.gyroEnergy > 0.5f && pending.accelToGyroRatio < 0.90f) return

        val durationMs = pending.durationSamples * (RESAMPLE_INTERVAL_NS / 1_000_000f)
        val features = TapFeatures(
            amplitude = pending.amplitude,
            durationMs = durationMs,
            accelEnergy = pending.accelEnergy,
            accelToGyroRatio = pending.accelToGyroRatio,
            decayRatio = decayRatio,
            gravityShift = pending.gravityShift
        )
        val score = classify(features)
        if (score < classifierThreshold()) return

        lastTapNs = pending.detectedNs
        onTapDetected(
            TapCandidate(
                detectedUptimeMs = pending.detectedUptimeMs,
                amplitude = pending.amplitude,
                durationMs = durationMs,
                accelEnergy = pending.accelEnergy,
                gyroEnergy = pending.gyroEnergy,
                accelToGyroRatio = pending.accelToGyroRatio,
                decayRatio = decayRatio,
                gravityShift = pending.gravityShift,
                score = score
            )
        )
    }

    private fun classify(features: TapFeatures): Float {
        val amplitudeScore =
            ((features.amplitude - currentThreshold) / (currentThreshold * 2.2f + EPSILON))
                .coerceIn(0f, 1.4f)

        val durationScore =
            (1f - abs(features.durationMs - 8.0f) / 6.0f).coerceIn(0f, 1.0f)

        val energyScore =
            ((features.accelEnergy - (currentThreshold * currentThreshold * 0.7f)) / 0.02f)
                .coerceIn(0f, 1.1f)

        val ratioScore = if (gyroAvailable) {
            ((features.accelToGyroRatio - 0.9f) / 1.8f).coerceIn(0f, 1.2f)
        } else {
            0.55f
        }

        val decayScore =
            ((0.90f - features.decayRatio) / 0.60f).coerceIn(0f, 1.1f)

        val gravityPenalty =
            (features.gravityShift / 0.55f).coerceIn(0f, 1.2f)

        val ratioWeight = if (gyroAvailable) 1.0f else 0.0f

        return 1.35f * amplitudeScore +
            0.90f * durationScore +
            0.80f * energyScore +
            ratioWeight * ratioScore +
            0.95f * decayScore -
+            1.25f * gravityPenalty
    }

    private fun classifierThreshold(): Float {
        return when {
            sensitivityMultiplier <= 1.5f -> 2.05f
            sensitivityMultiplier >= 3.0f -> 2.60f
            else -> 2.30f
        }
    }

    private fun averageRecentGyroEnergy(): Float {
        if (gyroHistoryCount == 0) return 0f

        val count = minOf(gyroHistoryCount, 6)
        var sum = 0f
        repeat(count) { i ->
            val index = (gyroHistoryHead - 1 - i + gyroEnergyHistory.size) % gyroEnergyHistory.size
            sum += gyroEnergyHistory[index]
        }
        return sum / count.toFloat()
    }

    private fun pushHistory(signalEnergy: Float, gravityShift: Float): Long {
        val sampleIndex = sampleCounter++
        val slot = (sampleIndex % HISTORY_SIZE).toInt()
        signalEnergyHistory[slot] = signalEnergy
        gravityShiftHistory[slot] = gravityShift
        sampleIdHistory[slot] = sampleIndex
        return sampleIndex
    }

    private fun averageHistory(history: FloatArray, startInclusive: Long, endInclusive: Long): Float {
        if (sampleCounter == 0L) return 0f
        val start = maxOf(0L, startInclusive)
        val end = minOf(sampleCounter - 1, endInclusive)
        if (start > end) return 0f

        var sum = 0f
        var count = 0
        for (i in start..end) {
            val slot = (i % HISTORY_SIZE).toInt()
            if (sampleIdHistory[slot] != i) continue
            sum += history[slot]
            count++
        }
        return if (count > 0) sum / count.toFloat() else 0f
    }

    fun reset() {
        resampler.reset()
        positivePeakDetector.reset()
        negativePeakDetector.reset()
        hpfLastIn = 0f
        hpfLastOut = 0f
        prevZ = 0f
        zInitialized = false
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        gravityInitialized = false
        gravityShiftEma = 0f
        noiseFloor = DEFAULT_NOISE_FLOOR
        currentThreshold = DEFAULT_NOISE_FLOOR
        lastTapNs = 0L
        gyroEnergySq = 0f
        gyroHistoryHead = 0
        gyroHistoryCount = 0
        pendingTap = null
        sampleCounter = 0L
        signalEnergyHistory.fill(0f)
        gravityShiftHistory.fill(0f)
        sampleIdHistory.fill(-1L)
        gyroEnergyHistory.fill(0f)
    }
}
