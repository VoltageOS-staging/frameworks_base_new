/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.wallpapers;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.app.WallpaperManager.SetWallpaperFlags;

import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.Trace;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.utils.windowmanager.WindowManagerProvider;
import com.android.systemui.atmosphere.AtmosphereController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

import javax.inject.Inject;

/**
 * Default built-in wallpaper that simply shows a static image.
 */
@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {

    private static final String TAG = ImageWallpaper.class.getSimpleName();
    private static final boolean DEBUG = false;

    // keep track of the number of pages of the launcher for local color extraction purposes
    private volatile int mPages = 1;
    private boolean mPagesComputed = false;

    private final UserTracker mUserTracker;
    private final WindowManagerProvider mWindowManagerProvider;

    private HandlerThread mWorker;
    @LongRunning
    private final DelayableExecutor mLongExecutor;

    private static final int DELAY_UNLOAD_BITMAP = 2000;

    @Inject
    public ImageWallpaper(@LongRunning DelayableExecutor longExecutor, UserTracker userTracker,
            WindowManagerProvider windowManagerProvider) {
        super();
        mLongExecutor = longExecutor;
        mUserTracker = userTracker;
        mWindowManagerProvider = windowManagerProvider;
    }

    @Override
    public Looper onProvideEngineLooper() {
        return mWorker != null ? mWorker.getLooper() : super.onProvideEngineLooper();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWorker = new HandlerThread(TAG);
        mWorker.start();
    }

    @Override
    public Engine onCreateEngine() {
        return new CanvasEngine();
    }

    class CanvasEngine extends WallpaperService.Engine implements DisplayListener {
        private WallpaperManager mWallpaperManager;
        private final ImageWallpaperColorExtractor mColorExtractor;
        private SurfaceHolder mSurfaceHolder;
        private boolean mDrawn = false;
        @VisibleForTesting
        static final int MIN_SURFACE_WIDTH = 128;
        @VisibleForTesting
        static final int MIN_SURFACE_HEIGHT = 128;
        private Bitmap mBitmap;
        private boolean mWideColorGamut = false;

        private int mBitmapUsages = 0;
        private final Object mLock = new Object();
        private final Object mSurfaceLock = new Object();
        
        private AtmosphereController mAtmosphereController;

        CanvasEngine() {
            super();
            setFixedSizeAllowed(true);
            setShowForAllUsers(true);
            mColorExtractor = new ImageWallpaperColorExtractor(
                    mLongExecutor,
                    mLock,
                    new ImageWallpaperColorExtractor.ImageWallpaperColorExtractorCallback() {

                        @Override
                        public void onColorsProcessed() {
                            CanvasEngine.this.notifyColorsChanged();
                        }

                        @Override
                        public void onColorsProcessed(List<RectF> regions,
                                List<WallpaperColors> colors) {
                            CanvasEngine.this.onColorsProcessed(regions, colors);
                        }

                        @Override
                        public void onMiniBitmapUpdated() {
                            CanvasEngine.this.onMiniBitmapUpdated();
                        }

                        @Override
                        public void onActivated() {
                            setOffsetNotificationsEnabled(true);
                        }

                        @Override
                        public void onDeactivated() {
                            setOffsetNotificationsEnabled(false);
                        }
                    });

            if (mPagesComputed) {
                mColorExtractor.onPageChanged(mPages);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            Trace.beginSection("ImageWallpaper.CanvasEngine#onCreate");
            if (DEBUG) {
                Log.d(TAG, "onCreate");
            }
            mWallpaperManager = getDisplayContext().getSystemService(WallpaperManager.class);
            mSurfaceHolder = surfaceHolder;
            Rect dimensions = mWallpaperManager.peekBitmapDimensionsAsUser(getSourceFlag(), true,
                    mUserTracker.getUserId());
            int width = Math.max(MIN_SURFACE_WIDTH, dimensions.width());
            int height = Math.max(MIN_SURFACE_HEIGHT, dimensions.height());
            mSurfaceHolder.setFixedSize(width, height);
            
            // Required for Touch Ripple and Depth Push
            setTouchEventsEnabled(true);

            getDisplayContext().getSystemService(DisplayManager.class)
                    .registerDisplayListener(this, null);
            getDisplaySizeAndUpdateColorExtractor();
            Trace.endSection();
            
            Rect frame = surfaceHolder.getSurfaceFrame();
            mAtmosphereController = new AtmosphereController(
                    getDisplayContext(),
                    getEngineSurfaceControl(),
                    frame.width(),
                    frame.height()
            );
        }

        private SurfaceControl getEngineSurfaceControl() {
            try {
                java.lang.reflect.Field field = android.service.wallpaper.WallpaperService.Engine.class.getDeclaredField("mSurfaceControl");
                field.setAccessible(true);
                return (SurfaceControl) field.get(this);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get SurfaceControl for Atmosphere", e);
                return null;
            }
        }

        @Override
        public void onDestroy() {
            Context context = getDisplayContext();
            if (context != null) {
                DisplayManager displayManager = context.getSystemService(DisplayManager.class);
                if (displayManager != null) displayManager.unregisterDisplayListener(this);
            }
            mColorExtractor.cleanUp();
            
            if (mAtmosphereController != null) {
                mAtmosphereController.destroy();
                mAtmosphereController = null;
            }
        }

        @Override
        public boolean shouldZoomOutWallpaper() {
            return true;
        }

        @Override
        public boolean shouldWaitForEngineShown() {
            return true;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (mAtmosphereController != null) {
                if (visible) mAtmosphereController.onResume();
                else mAtmosphereController.onPause();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceChanged: width=" + width + ", height=" + height);
            }
            if (mAtmosphereController != null) {
                mAtmosphereController.onSurfaceChanged(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (DEBUG) {
                Log.i(TAG, "onSurfaceDestroyed");
            }
            synchronized (mSurfaceLock) {
                mSurfaceHolder = null;
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            if (DEBUG) {
                Log.i(TAG, "onSurfaceCreated");
            }
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            if (DEBUG) {
                Log.d(TAG, "onSurfaceRedrawNeeded");
            }
            drawFrame();
        }

        private void drawFrame() {
            mLongExecutor.execute(this::drawFrameSynchronized);
        }

        private void drawFrameSynchronized() {
            synchronized (mLock) {
                if (mDrawn) return;
                drawFrameInternal();
            }
        }

        private void drawFrameInternal() {
            if (!isBitmapLoaded()) {
                loadWallpaperAndDrawFrameInternal();
            } else {
                synchronized (mSurfaceLock) {
                    if (mSurfaceHolder == null) {
                        Log.i(TAG, "Surface released before the image could be drawn");
                        return;
                    }
                    mBitmapUsages++;
                    drawFrameOnCanvas(mBitmap);
                    reportEngineShown(false);
                    unloadBitmapIfNotUsedInternal();
                }
            }
        }

        @VisibleForTesting
        void drawFrameOnCanvas(Bitmap bitmap) {
            Trace.beginSection("ImageWallpaper.CanvasEngine#drawFrame");
            Surface surface = mSurfaceHolder.getSurface();
            Canvas canvas = null;
            try {
                canvas = mWideColorGamut
                        ? surface.lockHardwareWideColorGamutCanvas()
                        : surface.lockHardwareCanvas();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Unable to lock canvas", e);
            }

            if (canvas != null) {
                Rect dest = mSurfaceHolder.getSurfaceFrame();
                try {
                    int blurType = SystemProperties.getInt("persist.sys.wallpaper.blur_enabled", 0);
                    if (blurType == 1 || (blurType == 2 && isLockScreenWallpaper()) || (blurType == 3 && !isLockScreenWallpaper())) {
                        int userBlurRadius;
                        switch (blurType) {
                            case 1: userBlurRadius = 200; break;
                            default: userBlurRadius = 25; break;
                        }
                        bitmap = WallpaperUtils.getBlurredBitmap(bitmap, userBlurRadius, getDisplayContext());
                    }
                    int dimType = SystemProperties.getInt("persist.sys.wallpaper.dim_enabled", 0);
                    if (dimType == 1 || (dimType == 2 && isLockScreenWallpaper()) || (dimType == 3 && !isLockScreenWallpaper())) {
                        int dimLevel = SystemProperties.getInt("persist.sys.wallpaper.dim_level", 10);
                        bitmap = WallpaperUtils.getDimmedBitmap(bitmap, dimLevel);
                    }
                    canvas.drawBitmap(bitmap, null, dest, null);
                    mDrawn = true;
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
            }
            Trace.endSection();
        }

        private boolean isLockScreenWallpaper() {
            return (this.getWallpaperFlags() & FLAG_LOCK) == FLAG_LOCK;
        }

        @VisibleForTesting
        boolean isBitmapLoaded() {
            return mBitmap != null && !mBitmap.isRecycled();
        }

        private void unloadBitmapIfNotUsed() {
            mLongExecutor.execute(this::unloadBitmapIfNotUsedSynchronized);
        }

        private void unloadBitmapIfNotUsedSynchronized() {
            synchronized (mLock) {
                unloadBitmapIfNotUsedInternal();
            }
        }

        private void unloadBitmapIfNotUsedInternal() {
            mBitmapUsages -= 1;
            if (mBitmapUsages <= 0) {
                mBitmapUsages = 0;
                unloadBitmapInternal();
            }
        }

        private void unloadBitmapInternal() {
            Trace.beginSection("ImageWallpaper.CanvasEngine#unloadBitmap");
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = null;
            synchronized (mSurfaceLock) {
                if (mSurfaceHolder != null) mSurfaceHolder.getSurface().hwuiDestroy();
            }
            mWallpaperManager.forgetLoadedWallpaper();
            Trace.endSection();
        }

        private void loadWallpaperAndDrawFrameInternal() {
            Trace.beginSection("WPMS.ImageWallpaper.CanvasEngine#loadWallpaper");
            boolean loadSuccess = false;
            Bitmap bitmap;

            try {
                Trace.beginSection("WPMS.getBitmapAsUser");
                bitmap = mWallpaperManager.getBitmapAsUser(
                        mUserTracker.getUserId(), false, getSourceFlag(), true);
                if (bitmap != null
                        && bitmap.getByteCount() > RecordingCanvas.MAX_BITMAP_SIZE) {
                    throw new RuntimeException("Wallpaper is too large to draw!");
                }
            } catch (RuntimeException | OutOfMemoryError exception) {
                Log.w(TAG, "Unable to load wallpaper!", exception);
                Trace.beginSection("WPMS.clearWallpaper");
                mWallpaperManager.clearWallpaper(getWallpaperFlags(), mUserTracker.getUserId());
                Trace.endSection();

                try {
                    Trace.beginSection("WPMS.getBitmapAsUser_defaultWallpaper");
                    bitmap = mWallpaperManager.getBitmapAsUser(
                            mUserTracker.getUserId(), false, getSourceFlag(), true);
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "Unable to load default wallpaper!", e);
                    bitmap = null;
                } finally {
                    Trace.endSection();
                }
            } finally {
                Trace.endSection();
            }

            if (bitmap == null) {
                Log.w(TAG, "Could not load bitmap");
            } else if (bitmap.isRecycled()) {
                Log.e(TAG, "Attempt to load a recycled bitmap");
            } else if (mBitmap == bitmap) {
                Log.e(TAG, "Loaded a bitmap that was already loaded");
            } else {
                loadSuccess = true;
                if (mBitmap != null) {
                    Trace.beginSection("WPMS.mBitmap.recycle");
                    mBitmap.recycle();
                    Trace.endSection();
                }
                mBitmap = bitmap;
                Trace.beginSection("WPMS.wallpaperSupportsWcg");
                mWideColorGamut = mWallpaperManager.wallpaperSupportsWcg(getSourceFlag());
                Trace.endSection();

                mBitmapUsages += 2;
                Trace.beginSection("WPMS.recomputeColorExtractorMiniBitmap");
                recomputeColorExtractorMiniBitmap();
                Trace.endSection();
                Trace.beginSection("WPMS.drawFrameInternal");
                drawFrameInternal();
                Trace.endSection();

                mLongExecutor.executeDelayed(
                        this::unloadBitmapIfNotUsedSynchronized, DELAY_UNLOAD_BITMAP);
            }
            if (!loadSuccess) reportEngineShown(false);
            Trace.endSection();
        }

        private void onColorsProcessed(List<RectF> regions, List<WallpaperColors> colors) {
            try {
                notifyLocalColorsChanged(regions, colors);
            } catch (RuntimeException e) {
                Log.e(TAG, e.getMessage(), e);
            }
        }

        private @SetWallpaperFlags int getSourceFlag() {
            return getWallpaperFlags() == FLAG_LOCK ? FLAG_LOCK : FLAG_SYSTEM;
        }

        @VisibleForTesting
        void recomputeColorExtractorMiniBitmap() {
            mColorExtractor.onBitmapChanged(mBitmap);
        }

        @VisibleForTesting
        void onMiniBitmapUpdated() {
            unloadBitmapIfNotUsed();
        }

        @Override
        public @Nullable WallpaperColors onComputeColors() {
            WallpaperColors colors = mColorExtractor.onComputeColors();
            if (mAtmosphereController != null) {
                mAtmosphereController.onWallpaperColorsChanged(colors);
            }
            return colors;
        }

        @Override
        public boolean supportsLocalColorExtraction() {
            return true;
        }

        @Override
        public void addLocalColorsAreas(@NonNull List<RectF> regions) {
            mColorExtractor.addLocalColorsAreas(regions);
        }

        @Override
        public void removeLocalColorsAreas(@NonNull List<RectF> regions) {
            mColorExtractor.removeLocalColorAreas(regions);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            if (mAtmosphereController != null) {
                mAtmosphereController.onTouchEvent(event);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xOffsetStep, float yOffsetStep,
                int xPixelOffset, int yPixelOffset) {
            final int pages;
            if (xOffsetStep > 0 && xOffsetStep <= 1) {
                pages = Math.round(1 / xOffsetStep) + 1;
            } else {
                pages = 1;
            }
            if (pages != mPages || !mPagesComputed) {
                mPages = pages;
                mPagesComputed = true;
                mColorExtractor.onPageChanged(mPages);
            }
            if (mAtmosphereController != null) {
                mAtmosphereController.onOffsetsChanged(xOffset);
            }
        }

        @Override
        public void onDimAmountChanged(float dimAmount) {
            mColorExtractor.onDimAmountChanged(dimAmount);
        }

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            Trace.beginSection("ImageWallpaper.CanvasEngine#onDisplayChanged");
            try {
                if (displayId == getDisplayContext().getDisplayId()) {
                    getDisplaySizeAndUpdateColorExtractor();
                }
            } finally {
                Trace.endSection();
            }
        }

        private void getDisplaySizeAndUpdateColorExtractor() {
            Rect window = mWindowManagerProvider.getWindowManager(getDisplayContext())
                    .getCurrentWindowMetrics()
                    .getBounds();
            mColorExtractor.setDisplayDimensions(window.width(), window.height());
        }

        @Override
        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            super.dump(prefix, fd, out, args);
            out.print(prefix); out.print("Engine="); out.println(this);
            out.print(prefix); out.print("valid surface=");
            out.println(getSurfaceHolder() != null && getSurfaceHolder().getSurface() != null
                    ? getSurfaceHolder().getSurface().isValid()
                    : "null");

            out.print(prefix); out.print("surface frame=");
            out.println(getSurfaceHolder() != null ? getSurfaceHolder().getSurfaceFrame() : "null");

            out.print(prefix); out.print("bitmap=");
            out.println(mBitmap == null ? "null"
                    : mBitmap.isRecycled() ? "recycled"
                    : mBitmap.getWidth() + "x" + mBitmap.getHeight());

            mColorExtractor.dump(prefix, fd, out, args);
        }
    }
}
