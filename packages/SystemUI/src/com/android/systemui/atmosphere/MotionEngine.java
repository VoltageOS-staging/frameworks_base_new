package com.android.systemui.atmosphere;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.os.Handler;
import android.os.HandlerThread;

public class MotionEngine implements SensorEventListener {
    private final SensorManager mSensorManager;
    private final Sensor mRotationSensor;
    private final PowerManager mPowerManager;
    private final OffsetListener mListener;
    private HandlerThread mSensorThread;
    private Handler mSensorHandler;

    private boolean mIsActive = false;
    private boolean mIsSleeping = false;
    private long mLastMovementTime = 0;

    private float mCurrentX = 0f;
    private float mCurrentY = 0f;
    
    // Rule 8: Micro-Motion Layer (0.5 - 2px shift)
    private final float mMaxOffset = 2.0f;
    
    // Rule 9: Temporal Smoothing
    private final float mSmoothing = 0.05f; 

    public interface OffsetListener {
        void onOffsetChanged(float x, float y);
    }

    public MotionEngine(Context context, OffsetListener listener) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mRotationSensor = mSensorManager != null ? mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mListener = listener;
    }

    public void start() {
        if (mIsActive || mRotationSensor == null) return;
        if (mPowerManager != null && mPowerManager.isPowerSaveMode()) return;

        if (mSensorThread == null) {
            mSensorThread = new HandlerThread("AtmosphereSensors");
            mSensorThread.start();
            mSensorHandler = new Handler(mSensorThread.getLooper());
        }

        mSensorManager.registerListener(this, mRotationSensor, SensorManager.SENSOR_DELAY_UI, mSensorHandler);
        mIsActive = true;
        mIsSleeping = false;
        mLastMovementTime = System.currentTimeMillis();
    }

    public void stop() {
        if (!mIsActive) return;
        mSensorManager.unregisterListener(this);
        mIsActive = false;
        mIsSleeping = true;
    }

    public void wakeUp() {
        mLastMovementTime = System.currentTimeMillis();
        if (mIsSleeping && mIsActive) {
            mSensorManager.registerListener(this, mRotationSensor, SensorManager.SENSOR_DELAY_UI, mSensorHandler);
            mIsSleeping = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mIsSleeping) return;

        float[] rotationMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        float[] orientation = new float[3];
        SensorManager.getOrientation(rotationMatrix, orientation);

        float targetX = Math.max(-mMaxOffset, Math.min(mMaxOffset, orientation[2] * mMaxOffset * 3f));
        float targetY = Math.max(-mMaxOffset, Math.min(mMaxOffset, orientation[1] * mMaxOffset * 3f));

        if (Math.abs(targetX - mCurrentX) > 0.01f || Math.abs(targetY - mCurrentY) > 0.01f) {
            mLastMovementTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - mLastMovementTime > 3000) {
            mSensorManager.unregisterListener(this);
            mIsSleeping = true;
            return;
        }

        // value = lerp(old, target, delta * smoothingFactor)
        mCurrentX += (targetX - mCurrentX) * mSmoothing;
        mCurrentY += (targetY - mCurrentY) * mSmoothing;

        mListener.onOffsetChanged(mCurrentX, mCurrentY);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
