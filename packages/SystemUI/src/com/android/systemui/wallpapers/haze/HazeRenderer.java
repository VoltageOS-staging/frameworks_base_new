package com.android.systemui.wallpapers.haze;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HazeRenderer {

    private static class BlobPhysics {
        float[] color;
        float startX, startY, p1x, p1y, endX, endY, startSize, endSize, massScale;
        BlobPhysics(float[] color, float sx, float sy, float ms) {
            this.color = color; startX = sx; startY = sy; massScale = ms;
        }
    }

    private int mProgramId;
    private int mBlurProgramId;
    private int mSharpTextureId;
    private int mBlurTextureId;
    private int mFboId;
    private float mAspectRatio = 1.0f;
    private FloatBuffer mVertexBuffer;

    private List<BlobPhysics> mBlobs = new ArrayList<>();
    private Random mRandom = new Random();
    private float[] mBlobColorsBuffer = new float[16 * 3];
    private float[] mBlobPosBuffer = new float[16 * 2];
    private float[] mBlobSizesBuffer = new float[16];

    public void init(int width, int height, Bitmap originalBitmap) {
        float[] vertices = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        };
        mVertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mVertexBuffer.put(vertices).position(0);

        mProgramId = createProgram(HazeShaders.VERTEX_SHADER, HazeShaders.HAZE_FRAGMENT_SHADER);
        mBlurProgramId = createProgram(HazeShaders.VERTEX_SHADER, HazeShaders.BLUR_FRAGMENT_SHADER);

        int[] fbo = new int[1];
        GLES30.glGenFramebuffers(1, fbo, 0);
        mFboId = fbo[0];

        loadTextures(originalBitmap, width, height);
        mAspectRatio = (float) width / height;
        GLES30.glViewport(0, 0, width, height);
    }

    private void loadTextures(Bitmap bitmap, int w, int h) {
        mSharpTextureId = uploadTexture(bitmap);
        mBlurTextureId = gpuBlur(mSharpTextureId, w, h, 20f);

        int extractW = Math.max(1, bitmap.getWidth() / 16);
        int extractH = Math.max(1, bitmap.getHeight() / 16);
        Bitmap smallBitmap = Bitmap.createScaledBitmap(bitmap, extractW, extractH, true);
        initBaseBlobs(smallBitmap);
        smallBitmap.recycle();
    }

    private void initBaseBlobs(Bitmap blurred) {
        List<HazeColorExtractor.ColorCluster> clusters = HazeColorExtractor.extractColors(blurred, 16);
        mBlobs.clear();
        for (HazeColorExtractor.ColorCluster c : clusters) {
            float[] clr = {Color.red(c.color)/255f, Color.green(c.color)/255f, Color.blue(c.color)/255f};
            mBlobs.add(new BlobPhysics(clr, c.centerX, c.centerY, 1.0f + (mRandom.nextFloat() * 0.4f)));
        }
        reRollTargets();
    }

    public void reRollTargets() {
        for (BlobPhysics b : mBlobs) {
            b.endX = 0.05f + mRandom.nextFloat() * 0.9f;
            b.endY = 0.05f + mRandom.nextFloat() * 0.9f;
            float midX = (b.startX + b.endX) / 2f;
            float midY = (b.startY + b.endY) / 2f;
            b.p1x = midX + (mRandom.nextFloat() - 0.5f) * 0.5f;
            b.p1y = midY + (mRandom.nextFloat() - 0.5f) * 0.5f;
            b.startSize = 0.05f;
            b.endSize = (0.12f + mRandom.nextFloat() * 0.08f) * b.massScale;
        }
    }

public void drawFrame(float blurStrength, float dimLevel, int style) {
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(mProgramId);

        float t = Math.max(0f, Math.min(1f, blurStrength));
        float physicsT = Math.max(0f, Math.min(1f, (t - 0.1f) / 0.9f));
        float progress = 1.0f - (float) Math.pow(1.0f - physicsT, 3);

        int idx = 0;
        // Only calculate Blob physics for styles that actually use them in the shader
        boolean enableBlobs = (style == 0 || style == 1 || style == 4);
        
        if (enableBlobs) {
            for (BlobPhysics b : mBlobs) {
                if (idx >= 16) break;
                float u = 1.0f - progress;
                float bx = (u*u * b.startX) + (2*u*progress * b.p1x) + (progress*progress * b.endX);
                float by = (u*u * b.startY) + (2*u*progress * b.p1y) + (progress*progress * b.endY);
                mBlobPosBuffer[idx*2] = bx; mBlobPosBuffer[idx*2+1] = by;
                mBlobSizesBuffer[idx] = b.startSize + (b.endSize - b.startSize) * progress;
                mBlobColorsBuffer[idx*3] = b.color[0]; mBlobColorsBuffer[idx*3+1] = b.color[1]; mBlobColorsBuffer[idx*3+2] = b.color[2];
                idx++;
            }
            if (idx > 0) {
                GLES30.glUniform3fv(GLES30.glGetUniformLocation(mProgramId, "uBlobColors"), idx, mBlobColorsBuffer, 0);
                GLES30.glUniform2fv(GLES30.glGetUniformLocation(mProgramId, "uBlobPositions"), idx, mBlobPosBuffer, 0);
                GLES30.glUniform1fv(GLES30.glGetUniformLocation(mProgramId, "uBlobSizes"), idx, mBlobSizesBuffer, 0);
            }
        }

        GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgramId, "uBlobCount"), idx);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uAspectRatio"), mAspectRatio);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uBlurStrength"), blurStrength);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mProgramId, "uDimLevel"), dimLevel);
        
        // Pass the actual style int to the shader instead of uEnableBlobs!
        GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgramId, "uStyle"), style);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSharpTextureId);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgramId, "uTextureSharp"), 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBlurTextureId);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(mProgramId, "uTextureBlur"), 1);

        int aPosLoc = GLES30.glGetAttribLocation(mProgramId, "aPosition");
        int aTexLoc = GLES30.glGetAttribLocation(mProgramId, "aTexCoord");
        mVertexBuffer.position(0);
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 16, mVertexBuffer);
        GLES30.glEnableVertexAttribArray(aPosLoc);
        mVertexBuffer.position(2);
        GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 16, mVertexBuffer);
        GLES30.glEnableVertexAttribArray(aTexLoc);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    public void destroy() {
        GLES30.glDeleteTextures(2, new int[]{mSharpTextureId, mBlurTextureId}, 0);
        GLES30.glDeleteFramebuffers(1, new int[]{mFboId}, 0);
        GLES30.glDeleteProgram(mProgramId);
        GLES30.glDeleteProgram(mBlurProgramId);
    }

    private int createEmptyTexture(int w, int h) {
        int[] t = new int[1]; GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private int gpuBlur(int input, int w, int h, float radius) {
        int blurW = Math.max(1, w / 8);
        int blurH = Math.max(1, h / 8);
        int output = createEmptyTexture(blurW, blurH);
        int temp = createEmptyTexture(blurW, blurH);
        GLES30.glUseProgram(mBlurProgramId);
        int aPos = GLES30.glGetAttribLocation(mBlurProgramId, "aPosition");
        int aTex = GLES30.glGetAttribLocation(mBlurProgramId, "aTexCoord");
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFboId);
        GLES30.glViewport(0, 0, blurW, blurH);
        
        // Pass 1: Horizontal
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, temp, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(mBlurProgramId, "uTexture"), 0);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(mBlurProgramId, "uDirection"), 1f, 0f);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(mBlurProgramId, "uRadius"), radius);
        drawQuad(aPos, aTex);
        
        // Pass 2: Vertical
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, output, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, temp);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(mBlurProgramId, "uDirection"), 0f, 1f);
        drawQuad(aPos, aTex);
        
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteTextures(1, new int[]{temp}, 0);
        return output;
    }

    private void drawQuad(int aPos, int aTex) {
        mVertexBuffer.position(0);
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, mVertexBuffer);
        GLES30.glEnableVertexAttribArray(aPos);
        mVertexBuffer.position(2);
        GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, mVertexBuffer);
        GLES30.glEnableVertexAttribArray(aTex);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int uploadTexture(Bitmap b) {
        int[] t = new int[1]; GLES30.glGenTextures(1, t, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        return t[0];
    }

    private int createProgram(String v, String f) {
        int vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER);
        GLES30.glShaderSource(vs, v); GLES30.glCompileShader(vs);
        int fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER);
        GLES30.glShaderSource(fs, f); GLES30.glCompileShader(fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs);
        GLES30.glLinkProgram(p);
        return p;
    }
}
