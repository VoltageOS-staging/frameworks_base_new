package com.android.systemui.atmosphere;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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
    private int mStyle = 0; // 0=Std, 1=Rev, 2=Frosted, 3=RevFrosted
    
    private boolean mIsEnabled = false;
    private boolean mIsVisible = false;
    private boolean mIsKilled = false;
    private boolean mWasLocked = true;
    private static final String TAG = "Atmosphere";

    // Paints
    private int[] mColors = new int[]{Color.DKGRAY, Color.RED, Color.GREEN, Color.BLUE};
    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCloud1Paint = new Paint(Paint.ANTI_ALIAS_FLAG); // Previously "Blobs"
    private final Paint mCloud2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCloud3Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNoisePaint = new Paint();
    
    // Shockwave Paint
    private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Animation States
    private float mParallaxX = 0f;
    private float mParallaxY = 0f;
    private float mPhase = 0f; 
    private float mCurrentAlpha = 0f;
    private float mTargetAlpha = 0f;

    // Shockwave State
    private float mTouchX = 0f;
    private float mTouchY = 0f;
    private float mWaveRadius = 0f;
    private float mWaveAlpha = 0f;
    private float mWaveThickness = 0f;

    private long mLastFrameTime = 0;
    private static final long FRAME_DELAY_MS = 33; 

    private final BroadcastReceiver mSystemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                boolean isReverse = (mStyle == 1 || mStyle == 3);
                mCurrentAlpha = isReverse ? 1f : 0f; // Reset to proper lockscreen state instantly
                mTargetAlpha = mCurrentAlpha;
                applyLayerAlpha();
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
        
        initPaints();
        updateSettings();
        
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mSystemReceiver, filter);
    }

    private void initPaints() {
        // Noise (Frosted Glass Grain)
        int noiseSize = 256;
        Bitmap noiseBitmap = Bitmap.createBitmap(noiseSize, noiseSize, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[noiseSize * noiseSize];
        for (int i = 0; i < pixels.length; i++) {
            int val = (int)(Math.random() * 255);
            pixels[i] = Color.argb(14, val, val, val); 
        }
        noiseBitmap.setPixels(pixels, 0, noiseSize, 0, 0, noiseSize, noiseSize);
        mNoisePaint.setShader(new android.graphics.BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mNoisePaint.setBlendMode(android.graphics.BlendMode.OVERLAY);

        // Shockwave setup
        mWavePaint.setStyle(Paint.Style.STROKE);
        mWavePaint.setColor(Color.WHITE);
        mWavePaint.setBlendMode(android.graphics.BlendMode.OVERLAY);
    }

    private void updateParallax(float x, float y) {
        mParallaxX = x;
        mParallaxY = y;
    }

    public void onTouchEvent(MotionEvent event) {
        if (!mIsEnabled || mIsKilled) return;
        mMotionEngine.wakeUp(); 
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            triggerShockwave(event.getX(), event.getY());
        }
    }

    private void triggerShockwave(float x, float y) {
        mTouchX = x;
        mTouchY = y;
        mWaveRadius = 50f;
        mWaveThickness = 150f;
        mWaveAlpha = 200f;
    }

    public void onWallpaperColorsChanged(WallpaperColors colors) {
        mColors = ColorEngine.extractAtmosphereColors(colors);
        precalculateAssets();
    }

    public void updateSettings() {
        if (mIsKilled) return;
        mIsEnabled = Settings.System.getIntForUser(mContext.getContentResolver(), "atmosphere_enabled", 0, UserHandle.USER_CURRENT) != 0;
        mIntensity = Settings.System.getIntForUser(mContext.getContentResolver(), "atmosphere_intensity", 50, UserHandle.USER_CURRENT);
        mStyle = Settings.System.getIntForUser(mContext.getContentResolver(), "atmosphere_style", 0, UserHandle.USER_CURRENT);
        
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
        
        mBasePaint.setColor(mColors[0]);

        // Screen mode for Volumetric Ambient Clouds
        mCloud1Paint.setBlendMode(android.graphics.BlendMode.SCREEN);
        mCloud2Paint.setBlendMode(android.graphics.BlendMode.SCREEN);
        mCloud3Paint.setBlendMode(android.graphics.BlendMode.SCREEN);

        float r = Math.max(mWidth, mHeight) * 0.9f;
        
        mCloud1Paint.setShader(new RadialGradient(0, 0, r, 
            new int[]{mColors[1], Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            
        mCloud2Paint.setShader(new RadialGradient(mWidth, mHeight, r, 
            new int[]{mColors[2], Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            
        mCloud3Paint.setShader(new RadialGradient(mWidth/2f, mHeight/2f, r, 
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
        
        if (mCurrentAlpha > 0.01f || mWaveAlpha > 0) {
            renderCanvas();
        }
        
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void updatePhysicsAndTransitions() {
        boolean isLocked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        
        // --- STYLE LOGIC ---
        // 0: Standard Atmosphere (Lock clear -> Home Clouds)
        // 1: Reverse Atmosphere  (Lock Clouds -> Home clear)
        // 2: Simple Frosted      (Lock clear -> Home Frosted)
        // 3: Reverse Frosted     (Lock Frosted -> Home clear)
        
        if (mStyle == 0 || mStyle == 2) {
            mTargetAlpha = isLocked ? 0f : 1f; // Clear on lockscreen
        } else {
            mTargetAlpha = isLocked ? 1f : 0f; // Blur/Atmosphere on lockscreen
        }
        
        if (mWasLocked && !isLocked) {
            mMotionEngine.wakeUp();
            triggerShockwave(mWidth / 2f, mHeight * 0.85f);
        }
        mWasLocked = isLocked;

        // Smooth Fade
        if (Math.abs(mTargetAlpha - mCurrentAlpha) > 0.01f) {
            mCurrentAlpha += (mTargetAlpha - mCurrentAlpha) * 0.20f; 
            applyLayerAlpha();
        }

        // Shockwave Math
        if (mWaveAlpha > 0) {
            mWaveRadius += mWidth * 0.08f; 
            mWaveThickness -= 5f; 
            mWaveAlpha -= 12f; 
            if (mWaveAlpha < 0) mWaveAlpha = 0;
            if (mWaveThickness < 1) mWaveThickness = 1;
        }

        mPhase += 0.015f; // Cloud drift speed
    }

    private void applyLayerAlpha() {
        if (mLayer != null) {
            // Hardware blur dynamically scales with the layer alpha!
            int blurRadius = (int)(150 * mCurrentAlpha);
            new SurfaceControl.Transaction()
                .setAlpha(mLayer, mCurrentAlpha)
                .setBackgroundBlurRadius(mLayer, blurRadius)
                .apply();
        }
    }

    private boolean renderCanvas() {
        Surface surface = new Surface(mLayer);
        if (!surface.isValid()) return false;
        
        Canvas canvas = surface.lockHardwareCanvas();
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.save();
            
            boolean drawClouds = (mStyle == 0 || mStyle == 1);

            // THE SWIRL TRANSITION
            // Calculate swirl relative to whether the layer is fading IN or fading OUT
            float animProgress = (mStyle == 0 || mStyle == 2) ? mCurrentAlpha : (1.0f - mCurrentAlpha);
            float swirlScale = 1.0f + (1.0f - animProgress) * 0.2f; 
            float swirlRotation = (1.0f - animProgress) * 15f; 
            
            canvas.scale(swirlScale, swirlScale, mTouchX > 0 ? mTouchX : mWidth/2f, mTouchY > 0 ? mTouchY : mHeight/2f);
            canvas.rotate(swirlRotation, mTouchX > 0 ? mTouchX : mWidth/2f, mTouchY > 0 ? mTouchY : mHeight/2f);
            
            canvas.translate(mParallaxX, mParallaxY);

            // 1. Draw Clouds & Tint (Only for Atmosphere styles)
            if (drawClouds) {
                canvas.drawRect(0, 0, mWidth, mHeight, mBasePaint);
                
                float maxPanX = mWidth * 0.4f;
                float maxPanY = mHeight * 0.3f;
                
                float pan1X = maxPanX * (float)Math.sin(mPhase * 0.04f + 1);
                float pan1Y = maxPanY * (float)Math.cos(mPhase * 0.02f + 2);
                float pan2X = maxPanX * (float)Math.sin(mPhase * 0.03f + 3);
                float pan2Y = maxPanY * (float)Math.cos(mPhase * 0.05f + 4);
                float pan3X = maxPanX * (float)Math.sin(mPhase * 0.05f + 5);
                float pan3Y = maxPanY * (float)Math.cos(mPhase * 0.03f + 6);

                canvas.save(); canvas.translate(pan1X, pan1Y);
                canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mCloud1Paint);
                canvas.restore();
                
                canvas.save(); canvas.translate(pan2X, pan2Y);
                canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mCloud2Paint);
                canvas.restore();
                
                canvas.save(); canvas.translate(pan3X, pan3Y);
                canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mCloud3Paint);
                canvas.restore();
            }

            // 2. Always draw Film Grain (Frosted Glass texture)
            canvas.drawRect(-mWidth*2, -mHeight*2, mWidth*3, mHeight*3, mNoisePaint);
            canvas.restore();

            // 3. Draw Fast Expanding Shockwave Ring (Over everything, un-swirled)
            if (mWaveAlpha > 0) {
                mWavePaint.setAlpha((int) mWaveAlpha);
                mWavePaint.setStrokeWidth(mWaveThickness);
                canvas.drawCircle(mTouchX, mTouchY, mWaveRadius, mWavePaint);
            }
            
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
    
    public void onOffsetsChanged(float xOffset) {}
    public void setWallpaperBitmap(Bitmap bitmap) {}
}
