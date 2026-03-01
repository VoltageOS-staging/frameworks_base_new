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
    
    // Max parallax increased, smoothing adjusted
    private final float mMaxOffset = 50.0f;
    private final float mSmoothing = 0.08f; 
    // Wait 10 seconds before sleeping to ensure you see the parallax
    private static final long SLEEP_TIMEOUT_MS = 10000;

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

        float targetX = Math.max(-mMaxOffset, Math.min(mMaxOffset, orientation[2] * mMaxOffset * 4f));
        float targetY = Math.max(-mMaxOffset, Math.min(mMaxOffset, orientation[1] * mMaxOffset * 4f));

        if (Math.abs(targetX - mCurrentX) > 0.1f || Math.abs(targetY - mCurrentY) > 0.1f) {
            mLastMovementTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - mLastMovementTime > SLEEP_TIMEOUT_MS) {
            mSensorManager.unregisterListener(this);
            mIsSleeping = true;
            return;
        }

        mCurrentX += (targetX - mCurrentX) * mSmoothing;
        mCurrentY += (targetY - mCurrentY) * mSmoothing;

        mListener.onOffsetChanged(mCurrentX, mCurrentY);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
