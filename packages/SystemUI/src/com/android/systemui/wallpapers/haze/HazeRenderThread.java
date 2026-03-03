package com.android.systemui.wallpapers.haze;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.provider.Settings;
import android.view.SurfaceHolder;
import android.view.animation.LinearInterpolator;

public class HazeRenderThread extends Thread {
    private final SurfaceHolder mHolder;
    private final Bitmap mBitmap;
    private final Context mContext;
    private boolean mRunning = true;
    private boolean mRenderRequested = true;

    private EGLDisplay mEglDisplay;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private HazeRenderer mRenderer;

    private ValueAnimator mAnimator;
    private float mCurrentBlur = 0f;
    private int mStyle = 0;
    private float mIntensity = 0.5f;
    
    // -1 = Uninitialized, 0 = Off, 1 = Lockscreen, 2 = Unlocked Homescreen
    private int mCurrentState = -1; 

    public HazeRenderThread(Context ctx, SurfaceHolder holder, Bitmap bitmap) {
        mContext = ctx;
        mHolder = holder;
        mBitmap = bitmap;
        updateSettings();
    }

    public void updateSettings() {
        mStyle = Settings.System.getInt(mContext.getContentResolver(), "atmosphere_style", 0);
        float rawIntensity = Settings.System.getInt(mContext.getContentResolver(), "atmosphere_intensity", 50) / 100f;
        mIntensity = rawIntensity * 0.55f; // Cap at 55% so 100 on slider doesn't go pitch black
    }

    public void triggerTransition(int state) {
        // PREVENTS ANIMATING WHEN RETURNING TO HOME SCREEN FROM APPS
        if (mCurrentState == state) return;
        mCurrentState = state;
        
        if (mAnimator != null) mAnimator.cancel();
        
        float target = 0f;
        long duration = 2000L;
        float start = mCurrentBlur;
        
        switch (mStyle) {
            case 0: // Standard: Sharp(0) on Lock, Haze(1) on Home
                target = (state == 2) ? 1f : 0f;
                break;
                
            case 1: // Reverse: Haze(1) on Lock, Sharp(0) on Home
                target = (state == 2) ? 0f : 1f;
                break;
                
            case 2: // Simple Frosted: Sharp(0) on Lock, Frost(1) on Home
                target = (state == 2) ? 1f : 0f;
                duration = 1500L;
                break;
                
            case 3: // Reverse Frosted: Frost(1) on Lock, Sharp(0) on Home
                target = (state == 2) ? 0f : 1f;
                duration = 1500L;
                break;
                
            case 4: // Double Haze
                if (state == 0) { 
                    start = 1f; target = 1f; duration = 0L;
                } else if (state == 1) { 
                    start = 1f; target = 0f; duration = 2500L;
                } else if (state == 2) { 
                    start = 0f; target = 1f; duration = 2000L;
                }
                break;
                
            case 5: // Vertical Melt: Melt(1) on Lock, Sharp(0) on Home
                target = (state == 2) ? 0f : 1f;
                duration = 2000L;
                break;
        }

        if (state == 0) {
            // Instantly snap to target state when screen turns off.
            mCurrentBlur = target;
            return;
        }

        mAnimator = ValueAnimator.ofFloat(start, target);
        mAnimator.setDuration(duration);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> {
            mCurrentBlur = (float) a.getAnimatedValue();
            requestRender();
        });
        mAnimator.start();
        
        if (state == 1 && mRenderer != null) {
            mRenderer.reRollTargets(); 
        }
    }

    public void requestRender() {
        synchronized (this) {
            mRenderRequested = true;
            notifyAll();
        }
    }

    public void quit() {
        mRunning = false;
        requestRender();
    }

    @Override
    public void run() {
        initEGL();
        mRenderer = new HazeRenderer();
        mRenderer.init(mHolder.getSurfaceFrame().width(), mHolder.getSurfaceFrame().height(), mBitmap);

        while (mRunning) {
            synchronized (this) {
                while (!mRenderRequested && mRunning) {
                    try { wait(); } catch (InterruptedException ignored) {}
                }
                mRenderRequested = false;
            }
            if (!mRunning) break;
            
            mRenderer.drawFrame(mCurrentBlur, mIntensity, mStyle);
            EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);
        }
        
        mRenderer.destroy();
        releaseEGL();
    }

    private void initEGL() {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(mEglDisplay, version, 0, version, 1);
        int[] configAttribs = {
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);
        int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        int[] surfaceAttribs = { EGL14.EGL_NONE };
        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], mHolder.getSurface(), surfaceAttribs, 0);
        EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext);
    }

    private void releaseEGL() {
        EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
        EGL14.eglDestroyContext(mEglDisplay, mEglContext);
        EGL14.eglTerminate(mEglDisplay);
    }
}
