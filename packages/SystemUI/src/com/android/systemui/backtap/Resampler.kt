/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.android.systemui.backtap

class Resampler(val intervalNs: Long = 2_400_000L) {
    private var prevX = 0f; private var prevY = 0f; private var prevZ = 0f
    private var prevT = 0L
    private var nextT = 0L
    private var initialised = false

    class Sample(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var tNs: Long = 0L)
    private val sample = Sample()

    fun update(x: Float, y: Float, z: Float, tNs: Long, onSample: (Sample) -> Unit) {
        if (!initialised) {
            prevX = x; prevY = y; prevZ = z; prevT = tNs
            nextT = tNs + intervalNs
            initialised = true
            return
        }
        if (tNs <= prevT) return

        while (nextT <= tNs) {
            val frac = (nextT - prevT).toFloat() / (tNs - prevT).toFloat()
            sample.x = prevX + frac * (x - prevX)
            sample.y = prevY + frac * (y - prevY)
            sample.z = prevZ + frac * (z - prevZ)
            sample.tNs = nextT
            
            onSample(sample)
            nextT += intervalNs
        }
        prevX = x; prevY = y; prevZ = z; prevT = tNs
    }

    fun reset() { initialised = false; nextT = 0L }
}
