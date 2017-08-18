package com.zego.livedemo5.videofilter;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.util.Log;

import com.zego.livedemo5.videocapture.ve_gl.GlRectDrawer;
import com.zego.livedemo5.videocapture.ve_gl.GlUtil;
import com.zego.zegoliveroom.videofilter.ZegoVideoFilter;

import java.nio.ByteBuffer;

/**
 * Created by robotding on 17/2/23.
 */

public class VideoFilterGlTexture2dDemo extends ZegoVideoFilter {
    private static final String TAG = "VideoFilterGlTexture2d";

    private ZegoVideoFilter.Client mClient = null;

    private GlRectDrawer mDrawer;
    private int mTextureId = 0;
    private int mFrameBufferId = 0;
    private float[] transformationMatrix = new float[]{1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f};

    private int mWidth = 0;
    private int mHeight = 0;

    @Override
    protected void allocateAndStart(Client client) {
        Log.e(TAG, "allocateAndStart");
        mClient = client;
        mWidth = mHeight = 0;
        if (mDrawer == null) {
            mDrawer = new GlRectDrawer();
        }
    }

    @Override
    protected void stopAndDeAllocate() {
        Log.e(TAG, "stopAndDeAllocate");
        if (mTextureId != 0) {
            int[] textures = new int[]{mTextureId};
            GLES20.glDeleteTextures(1, textures, 0);
            mTextureId = 0;
        }

        if (mFrameBufferId != 0) {
            int[] frameBuffers = new int[]{mFrameBufferId};
            GLES20.glDeleteFramebuffers(1, frameBuffers, 0);
            mFrameBufferId = 0;
        }

        if (mDrawer != null) {
            mDrawer.release();
            mDrawer = null;
        }

        mClient.destroy();
        mClient = null;
    }

    @Override
    protected int supportBufferType() {
        Log.e(TAG, "supportBufferType");
        return BUFFER_TYPE_SYNC_GL_TEXTURE_2D;
    }

    @Override
    protected int dequeueInputBuffer(int width, int height, int stride) {
        Log.e(TAG, "dequeueInputBuffer");
        return 0;
    }

    @Override
    protected ByteBuffer getInputBuffer(int index) {
        Log.e(TAG, "getInputBuffer");
        return null;
    }

    @Override
    protected void queueInputBuffer(int bufferIndex, int width, int height, int stride, long timestamp_100n) {
        Log.e(TAG, "queueInputBuffer");
    }

    @Override
    protected SurfaceTexture getSurfaceTexture() {
        Log.e(TAG, "getSurfaceTexture");
        return null;
    }

    @Override
    protected void onProcessCallback(int textureId, int width, int height, long timestamp_100n) {
        Log.e(TAG, "onProcessCallback");
        if (mWidth != width || mHeight != height) {
            if (mTextureId != 0) {
                int[] textures = new int[]{mTextureId};
                GLES20.glDeleteTextures(1, textures, 0);
                mTextureId = 0;
            }

            if (mFrameBufferId != 0) {
                int[] frameBuffers = new int[]{mFrameBufferId};
                GLES20.glDeleteFramebuffers(1, frameBuffers, 0);
                mFrameBufferId = 0;
            }

            mWidth = width;
            mHeight = height;
        }

        if (mTextureId == 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            mTextureId = GlUtil.generateTexture(GLES20.GL_TEXTURE_2D);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

            mFrameBufferId = GlUtil.generateFrameBuffer(mTextureId);
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBufferId);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        mDrawer.drawRgb(textureId, transformationMatrix,
                width, height, 0, 0, width, height);

        mClient.onProcessCallback(mTextureId, width, height, timestamp_100n);
    }
}
