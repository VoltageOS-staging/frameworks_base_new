package com.android.systemui.atmosphere;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.app.WallpaperColors;

public class AtmosphereController implements Choreographer.FrameCallback {
    private final Context mContext;
    private final SurfaceControl mParentSurface;
    private SurfaceControl mLayer;
    private final MotionEngine mMotionEngine;
    private final KeyguardManager mKeyguardManager;
    
    private int mWidth;
    private int mHeight;
    private int mIntensity = 50;
    
    private boolean mIsEnabled = false;
    private boolean mIsVisible = false;
    private boolean mIsKilled = false;
    private static final String TAG = "Atmosphere";

    // Reactive State
    private boolean mIsNightMode = false;
    private boolean mIsCharging = false;
    private boolean mIsLowBattery = false;

    // Nothing OS Color Mesh
    private Bitmap mBlurredMeshBitmap;
    private final Paint mMeshPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mCompressionPaint = new Paint();
    private final Paint mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Animation & Physics States
    private float mParallaxX = 0f;
    private float mParallaxY = 0f;
    private float mPhase = 0f; // Controls breathing speed
    private float mCurrentAlpha = 0f;
    private float mTargetAlpha = 0f;
    private float mCurrentScale = 1.0f;
    private float mTargetScale = 1.0f;

    // Touch Ripple State
    private float mTouchX = 0f;
    private float mTouchY = 0f;
    private float mRippleAlpha = 0f;

    // Performance Profiling
    private long mLastFrameTime = 0;
    private static final long FRAME_DELAY_MS = 33; // 30 FPS Cap
    private int mHeavyFramesCount = 0;

    private final BroadcastReceiver mSystemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Instantly hide atmosphere to prepare for sharp lockscreen wake
                mCurrentAlpha = 0f;
                mTargetAlpha = 0f;
                applyLayerAlpha();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                // Context-Reactive Lighting
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                mIsLowBattery = (level * 100 / (float)scale) <= 15.0f;
                precalculateAssets();
            }
        }
    };

    public AtmosphereController(Context context, SurfaceControl parentSurface, int width, int height) {
        mContext = context;
        mParentSurface = parentSurface;
        mWidth = width;
        mHeight = height;

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.isLowRamDevice()) mIsKilled = true;
        
        mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        mMotionEngine = new MotionEngine(context, this::updateParallax);
        
        updateSettings();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mSystemReceiver, filter);
        checkNightMode();
    }

    public void setWallpaperBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || mWidth <= 0) return;
        try {
            if (mBlurredMeshBitmap != null && !mBlurredMeshBitmap.isRecycled()) mBlurredMeshBitmap.recycle();
            // Extreme downscale creates the seamless procedural color blobs
            mBlurredMeshBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true);
        } catch (Exception e) {
            Log.e("Atmosphere", "Failed to generate color mesh", e);
        }
    }

    private void checkNightMode() {
        int nightModeFlags = mContext.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mIsNightMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void updateParallax(float x, float y) {
        mParallaxX = x;
        mParallaxY = y;
    }

    public void onTouchEvent(MotionEvent event) {
        if (!mIsEnabled || mIsKilled || mCurrentAlpha < 0.1f) return;
        mMotionEngine.wakeUp(); 
        
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mTargetScale = 0.990f; // Touch Depth Push
            mTouchX = event.getX();
            mTouchY = event.getY();
            mRippleAlpha = 120f; // Bright touch ripple
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            mTargetScale = 1.0f; // Release depth push
        }
    }
    
    public void onOffsetsChanged(float xOffset) {}

    public void onWallpaperColorsChanged(WallpaperColors colors) {
        }

    public void updateSettings() {
        if (mIsKilled) return;
        mIsEnabled = Settings.System.getIntForUser(mContext.getContentResolver(), "atmosphere_enabled", 0, UserHandle.USER_CURRENT) != 0;
        mIntensity = Settings.System.getIntForUser(mContext.getContentResolver(), "atmosphere_intensity", 50, UserHandle.USER_CURRENT);
        
        checkNightMode();
        precalculateAssets();
        
        if (mIsEnabled) {
            createOrUpdateLayer();
            if (mIsVisible) {
                mMotionEngine.start();
                Choreographer.getInstance().postFrameCallback(this);
            }
        } else {
            mMotionEngine.stop();
            destroyLayer();
        }
    }

    public void onSurfaceChanged(int width, int height) {
        if (mWidth != width || mHeight != height) {
            mWidth = width;
            mHeight = height;
            precalculateAssets();
            destroyLayer();
            if (mIsEnabled) createOrUpdateLayer();
        }
    }

    private void precalculateAssets() {
        if (mWidth <= 0 || mHeight <= 0) return;
        // Luminance Compression: Soft dark overlay
        int compAlpha = mIsNightMode ? (int)(140 * (mIntensity/100f)) : (int)(60 * (mIntensity/100f));
        mCompressionPaint.setColor(Color.argb(compAlpha, 15, 15, 20));
        mCompressionPaint.setBlendMode(mIsNightMode ? android.graphics.BlendMode.DARKEN : android.graphics.BlendMode.MULTIPLY);
    }

    private void createOrUpdateLayer() {
        if (mParentSurface == null) return;
        if (mLayer == null) {
            mLayer = new SurfaceControl.Builder()
                    .setName("AtmosphereLayer")
                    .setBufferSize(mWidth, mHeight)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setParent(mParentSurface)
                    .build();

            // Set Z=1 and apply hardware blur field for maximum smoothness
            new SurfaceControl.Transaction()
                .show(mLayer)
                .setLayer(mLayer, 1)
                .setBackgroundBlurRadius(mLayer, 40)
                .setAlpha(mLayer, 0f)
                .apply();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!mIsEnabled || !mIsVisible || mLayer == null || mIsKilled) return;
        
        long now = SystemClock.uptimeMillis();
        if (now - mLastFrameTime < FRAME_DELAY_MS) {
            Choreographer.getInstance().postFrameCallback(this);
            return; 
        }
        mLastFrameTime = now;

        updatePhysicsAndTransitions();
        
        // GPU Optimization: Only render canvas if the layer is actually visible
        if (mCurrentAlpha > 0.01f) {
            long startNs = System.nanoTime();
            boolean drew = renderCanvas();
            long durationMs = (System.nanoTime() - startNs) / 1000000;
            
            if (drew && durationMs > 32) {
                mHeavyFramesCount++;
                if (mHeavyFramesCount > 60) {
                    Log.e(TAG, "Atmosphere killed: Exceeded 32ms render budget 60 times.");
                    mIsKilled = true;
                    destroy();
                    return;
                }
            } else if (drew) {
                mHeavyFramesCount = 0;
            }
        }
        
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updatePhysicsAndTransitions() {
        // 1. Lock/Unlock Transitions
        boolean isLocked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        mTargetAlpha = isLocked ? 0f : 1f;
        
        // 2. Context-Reactive Alpha (Dim on low battery)
        float batteryPenalty = mIsLowBattery ? 0.6f : 1.0f;
        mTargetAlpha *= batteryPenalty;

        // Smoothly fade between Lockscreen (sharp) and Homescreen (Atmosphere)
        if (Math.abs(mTargetAlpha - mCurrentAlpha) > 0.01f) {
            mCurrentAlpha += (mTargetAlpha - mCurrentAlpha) * 0.12f;
            applyLayerAlpha();
        }

        // 3. Touch Ripple Fade
        if (mRippleAlpha > 0) {
            mRippleAlpha -= 8f;
            if (mRippleAlpha < 0) mRippleAlpha = 0;
        }

        // 4. Depth Push Scaling
        mCurrentScale += (mTargetScale - mCurrentScale) * 0.15f;

        // 5. Breathing Phase (Speeds up if plugged into charger)
        float speedMult = mIsCharging ? 2.5f : 1.0f;
        mPhase += (0.01f * speedMult);
    }

    private void applyLayerAlpha() {
        if (mLayer != null) {
            float intensityCap = Math.max(0.3f, mIntensity / 100f);
            new SurfaceControl.Transaction().setAlpha(mLayer, mCurrentAlpha * intensityCap).apply();
        }
    }

    private boolean renderCanvas() {
        Surface surface = new Surface(mLayer);
        if (!surface.isValid() || mBlurredMeshBitmap == null || mBlurredMeshBitmap.isRecycled()) return false;
        
        Canvas canvas = surface.lockHardwareCanvas();
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            
            // Organic Breathing & Panning (Driven by mPhase)
            float breatheScale = 1.0f + 0.08f * (float)Math.sin(mPhase * 0.05f);
            float panX = 60f * (float)Math.sin(mPhase * 0.03f);
            float panY = 60f * (float)Math.cos(mPhase * 0.04f);

            canvas.save();
            
            // Apply Touch Depth Push scale
            float totalScale = breatheScale * mCurrentScale;
            canvas.scale(totalScale, totalScale, mWidth/2f, mHeight/2f);
            
            // Apply Panning and Micro-Parallax
            canvas.translate(mParallaxX, mParallaxY);
            
            // 1. Draw Massive Blurred Color Blobs
            RectF dest = new RectF(-150 + panX, -150 + panY, mWidth + 150 + panX, mHeight + 150 + panY);
            canvas.drawBitmap(mBlurredMeshBitmap, null, dest, mMeshPaint);
            
            // 2. Draw Luminance Compression
            canvas.drawRect(dest, mCompressionPaint);

            // 3. Draw Touch Ripple Overlay
            if (mRippleAlpha > 0) {
                mRipplePaint.setShader(new RadialGradient(mTouchX, mTouchY, 350f, 
                    Color.argb((int) mRippleAlpha, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                mRipplePaint.setBlendMode(android.graphics.BlendMode.SCREEN);
                canvas.drawCircle(mTouchX, mTouchY, 350f, mRipplePaint);
            }
            
            canvas.restore();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            surface.unlockCanvasAndPost(canvas);
            surface.release();
        }
    }

    public void destroy() {
        try { mContext.unregisterReceiver(mSystemReceiver); } catch (Exception ignored) {}
        mMotionEngine.stop();
        mIsVisible = false;
        if (mBlurredMeshBitmap != null && !mBlurredMeshBitmap.isRecycled()) {
            mBlurredMeshBitmap.recycle();
            mBlurredMeshBitmap = null;
        }
        destroyLayer();
    }

    private void destroyLayer() {
        if (mLayer != null) {
            new SurfaceControl.Transaction().remove(mLayer).apply();
            mLayer.release();
            mLayer = null;
        }
    }

    public void onResume() {
        mIsVisible = true;
        updateSettings();
    }

    public void onPause() {
        mIsVisible = false;
        mMotionEngine.stop();
    }
}
