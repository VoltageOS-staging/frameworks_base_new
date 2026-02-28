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

    // Generative Colors & Paints
    private int[] mColors = new int[]{Color.DKGRAY, Color.RED, Color.GREEN, Color.BLUE};
    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBlob1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBlob2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBlob3Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNoisePaint = new Paint();
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
        
        initNoise();
        updateSettings();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mSystemReceiver, filter);
        checkNightMode();
    }

    private void initNoise() {
        int noiseSize = 256;
        Bitmap noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[noiseSize * noiseSize];
        for (int i = 0; i < pixels.length; i++) {
            int val = (int)(Math.random() * 255);
            pixels[i] = Color.argb(18, val, val, val); // 18 Alpha = Beautiful Frosted Glass
        }
        noiseBitmap.setPixels(pixels, 0, noiseSize, 0, 0, noiseSize, noiseSize);
        mNoisePaint.setShader(new android.graphics.BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoisePaint.setBlendMode(android.graphics.BlendMode.OVERLAY);
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
        mColors = ColorEngine.extractAtmosphereColors(colors);
        precalculateAssets();
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
        
        // Use SCREEN blend mode so overlapping blobs add light to each other (Color Pop!)
        mBasePaint.setBlendMode(android.graphics.BlendMode.SCREEN);
        mBlob1Paint.setBlendMode(android.graphics.BlendMode.SCREEN);
        mBlob2Paint.setBlendMode(android.graphics.BlendMode.SCREEN);
        mBlob3Paint.setBlendMode(android.graphics.BlendMode.SCREEN);

        // Huge radii so they act like soft, diffuse ambient light
        float r = Math.max(mWidth, mHeight) * 1.5f;
        
        mBasePaint.setShader(new RadialGradient(mWidth/2f, mHeight/2f, r, 
            new int[]{mColors[0], Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            
        mBlob1Paint.setShader(new RadialGradient(0, 0, r, 
            new int[]{mColors[1], Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            
        mBlob2Paint.setShader(new RadialGradient(mWidth, mHeight, r, 
            new int[]{mColors[2], Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            
        mBlob3Paint.setShader(new RadialGradient(mWidth/2f, mHeight/2f, r, 
            new int[]{mColors[3], Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
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

            new SurfaceControl.Transaction()
                .show(mLayer)
                .setLayer(mLayer, 1)
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
        boolean isLocked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        mTargetAlpha = isLocked ? 0f : 1f;
        
        float batteryPenalty = mIsLowBattery ? 0.6f : 1.0f;
        mTargetAlpha *= batteryPenalty;

        if (Math.abs(mTargetAlpha - mCurrentAlpha) > 0.01f) {
            mCurrentAlpha += (mTargetAlpha - mCurrentAlpha) * 0.12f;
            applyLayerAlpha();
        }

        if (mRippleAlpha > 0) {
            mRippleAlpha -= 8f;
            if (mRippleAlpha < 0) mRippleAlpha = 0;
        }

        mCurrentScale += (mTargetScale - mCurrentScale) * 0.15f;

        float speedMult = mIsCharging ? 2.5f : 1.0f;
        mPhase += (0.01f * speedMult);
    }

    private void applyLayerAlpha() {
        if (mLayer != null) {
            float intensityCap = Math.max(0.3f, mIntensity / 100f);
            // As the Opaque Generative mesh fades in, Android simultaneously blurs the lockscreen underneath it!
            int blurRadius = (int)(100 * mCurrentAlpha);
            new SurfaceControl.Transaction()
                .setAlpha(mLayer, mCurrentAlpha * intensityCap)
                .setBackgroundBlurRadius(mLayer, blurRadius)
                .apply();
        }
    }

    private boolean renderCanvas() {
        Surface surface = new Surface(mLayer);
        if (!surface.isValid()) return false;
        
        Canvas canvas = surface.lockHardwareCanvas();
        try {
            // Fill with solid black first so SCREEN blend mode pops the colors correctly
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC);
            
            canvas.save();
            float breatheScale = 1.0f + 0.1f * (float)Math.sin(mPhase * 0.05f);
            float totalScale = breatheScale * mCurrentScale;
            canvas.scale(totalScale, totalScale, mWidth/2f, mHeight/2f);
            canvas.translate(mParallaxX, mParallaxY);
            
            // Lava-Lamp Autonomous Motion (Independent Panning Math for each Blob)
            float panBaseX = 150f * (float)Math.sin(mPhase * 0.02f);
            float panBaseY = 150f * (float)Math.cos(mPhase * 0.03f);
            
            float pan1X = 250f * (float)Math.sin(mPhase * 0.04f + 1);
            float pan1Y = 250f * (float)Math.cos(mPhase * 0.02f + 2);
            
            float pan2X = 250f * (float)Math.sin(mPhase * 0.03f + 3);
            float pan2Y = 250f * (float)Math.cos(mPhase * 0.05f + 4);
            
            float pan3X = 250f * (float)Math.sin(mPhase * 0.05f + 5);
            float pan3Y = 250f * (float)Math.cos(mPhase * 0.03f + 6);

            // Draw Base Blob
            canvas.save(); canvas.translate(panBaseX, panBaseY);
            canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mBasePaint);
            canvas.restore();

            // Draw Neon Blobs ON TOP
            canvas.save(); canvas.translate(pan1X, pan1Y);
            canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mBlob1Paint);
            canvas.restore();
            
            canvas.save(); canvas.translate(pan2X, pan2Y);
            canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mBlob2Paint);
            canvas.restore();
            
            canvas.save(); canvas.translate(pan3X, pan3Y);
            canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mBlob3Paint);
            canvas.restore();

            // Draw Frosted Glass Film Grain
            canvas.drawRect(-mWidth, -mHeight, mWidth*2, mHeight*2, mNoisePaint);

            // Draw Touch Ripple Overlay
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
