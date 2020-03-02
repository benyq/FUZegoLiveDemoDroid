package com.zego.livedemo5.videocapture;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import com.faceunity.nama.FURenderer;
import com.zego.livedemo5.ZegoApiManager;
import com.zego.livedemo5.videocapture.ve_gl.EglBase;
import com.zego.livedemo5.videocapture.ve_gl.EglBase14;
import com.zego.livedemo5.videocapture.ve_gl.GlRectDrawer;
import com.zego.livedemo5.videocapture.ve_gl.GlUtil;
import com.zego.zegoavkit2.ZegoVideoCaptureDevice;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by robotding on 17/5/3.
 */
public class FUVideoCaptureFromCamera2 extends ZegoVideoCaptureDevice implements
        SurfaceTexture.OnFrameAvailableListener,
        TextureView.SurfaceTextureListener,
        Camera.PreviewCallback {
    private static final String TAG = "FUVideoCaptureFromCam2";
    private static final int CAMERA_STOP_TIMEOUT_MS = 7000;

    private Camera mCam = null;
    private Camera.CameraInfo mCamInfo = null;
    private int mFront = 1; // 默认前置相机
    private int mCameraWidth = 640;
    private int mCameraHeight = 480;
    private int mCaptureWidth = 0;
    private int mCaptureHeight = 0;
    private int mViewWidth = 0;
    private int mViewHeight = 0;
    private int mViewMode = 0;
    private int mFrameRate = 15;
    private int mDisplayRotation = 0;
    private int mImageRotation = 0;

    private Client mClient = null;

    private EglBase mDummyContext = null;
    private GlRectDrawer mDummyDrawer = null;
    private boolean mIsEgl14 = false;
    private int mInputTextureId = 0;
    private SurfaceTexture mInputSurfaceTexture = null;
    private float[] mInputMatrix = new float[16];
    private int mTextureId = 0;
    private int mFrameBufferId = 0;

    private float[] mIdentityMatrix = new float[]{1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f};

    private HandlerThread mThread = null;
    private volatile Handler cameraThreadHandler = null;
    private final AtomicBoolean isCameraRunning = new AtomicBoolean();
    private final Object pendingCameraRestartLock = new Object();
    private volatile boolean pendingCameraRestart = false;

    private boolean mIsPreview = false;
    private EglBase previewEglBase = null;
    private GlRectDrawer previewDrawer = null;
    private float[] mPreviewMatrix = new float[16];

    private boolean mIsCapture = false;
    private EglBase captureEglBase = null;
    private GlRectDrawer captureDrawer = null;
    private float[] mCaptureMatrix = new float[16];

    private TextureView mTextureView = null;

    private FURenderer mFURenderer;
    private Context mContext;
    private ZegoApiManager.OnFURendererCreatedListener fuRendererCompleteListener;


    public FUVideoCaptureFromCamera2(Context context) {
        this.mContext = context;
    }

    public FUVideoCaptureFromCamera2(Context context, ZegoApiManager.OnFURendererCreatedListener listener) {
        this.mContext = context;
        this.fuRendererCompleteListener = listener;
    }

    @Override
    protected void allocateAndStart(Client client) {
        Log.d(TAG, "allocateAndStart");
        mClient = client;

        mThread = new HandlerThread("camera-cap");
        mThread.start();
        cameraThreadHandler = new Handler(mThread.getLooper());

        mFURenderer = new FURenderer.Builder(mContext)
                .setInputTextureType(FURenderer.INPUT_EXTERNAL_OES_TEXTURE)
                .setInputImageOrientation(FURenderer.getCameraOrientation(
                        mFront == 1 ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK))
                .build();
        if (fuRendererCompleteListener != null) {
            fuRendererCompleteListener.onCreated(mFURenderer);
        }

        Log.e(TAG, "threadId=" + cameraThreadHandler.getLooper().getThread().getId() + "--threadName="
                + cameraThreadHandler.getLooper().getThread().getName());

        final CountDownLatch barrier = new CountDownLatch(1);
        cameraThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                mDummyContext = EglBase.create(null, EglBase.CONFIG_PIXEL_BUFFER);

                try {
                    mDummyContext.createDummyPbufferSurface();
                    mDummyContext.makeCurrent();
                    mDummyDrawer = new GlRectDrawer();
                } catch (RuntimeException e) {
                    // Clean up before rethrowing the exception.
                    mDummyContext.releaseSurface();
                    e.printStackTrace();
                    throw e;
                }

                mIsEgl14 = EglBase14.isEGL14Supported();
                mInputTextureId = GlUtil.generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
                mInputSurfaceTexture = new SurfaceTexture(mInputTextureId);
                mInputSurfaceTexture.setOnFrameAvailableListener(FUVideoCaptureFromCamera2.this);

                if (mFURenderer != null) {
                    mFURenderer.onSurfaceCreated();
                }
                barrier.countDown();
            }
        });
        try {
            barrier.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void stopAndDeAllocate() {
        Log.d(TAG, "stopAndDeAllocate");
        stopCapture();

        if (cameraThreadHandler != null) {
            final CountDownLatch barrier = new CountDownLatch(1);
            cameraThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mFURenderer != null) {
                        mFURenderer.onSurfaceDestroyed();
                        mFURenderer = null;
                    }

                    releaseCaptureSurface();

                    if (captureEglBase != null) {
                        captureEglBase.release();
                        captureEglBase = null;
                    }

                    releasePreviewSurface();
                    if (previewDrawer != null) {
                        previewDrawer.release();
                        previewDrawer = null;
                    }
                    if (captureEglBase != null) {
                        captureEglBase.release();

                        captureEglBase = null;
                    }
                    if (mTextureView != null) {
                        if (mTextureView.getSurfaceTextureListener().equals(FUVideoCaptureFromCamera2.this)) {
                            mTextureView.setSurfaceTextureListener(null);
                        }
                        mTextureView = null;
                    }

                    mInputSurfaceTexture.release();
                    mInputSurfaceTexture = null;

                    mDummyContext.makeCurrent();
                    deleteFBO();
                    if (mInputTextureId != 0) {
                        int[] textures = new int[]{mInputTextureId};
                        GLES20.glDeleteTextures(1, textures, 0);
                        mInputTextureId = 0;
                    }

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

                    mDummyDrawer = null;
                    mDummyContext.release();
                    mDummyContext = null;

                    barrier.countDown();
                }
            });
            try {
                barrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mThread.quit();
        mThread = null;

        mClient.destroy();
        mClient = null;
    }

    @Override
    protected int startCapture() {
        Log.d(TAG, "startCapture");
        mIsCapture = true;
        startCamera();
        return 0;
    }

    @Override
    protected int stopCapture() {
        Log.d(TAG, "stopCapture");
        mIsCapture = false;
        stopCamera();
        return 0;
    }

    protected int startCamera() {
        if (isCameraRunning.getAndSet(true)) {
            Log.e(TAG, "Camera has already been started.");
            return 0;
        }

        final boolean didPost = maybePostOnCameraThread(new Runnable() {
            @Override
            public void run() {
                // * Create and Start Cam
                createCamOnCameraThread();
                startCamOnCameraThread();
            }
        });

        return 0;
    }

    protected int stopCamera() {
        if (mIsPreview || mIsCapture) {
            return 0;
        }

        final CountDownLatch barrier = new CountDownLatch(1);
        final boolean didPost = maybePostOnCameraThread(new Runnable() {
            @Override
            public void run() {
                stopCaptureOnCameraThread(true /* stopHandler */);
                releaseCam();
                barrier.countDown();
            }
        });
        if (!didPost) {
            Log.e(TAG, "Calling stopCapture() for already stopped camera.");
            return 0;
        }
        try {
            if (!barrier.await(CAMERA_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Camera stop timeout");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.d(TAG, "stopCapture done");

        return 0;
    }

    @Override
    protected int supportBufferType() {
        return PIXEL_BUFFER_TYPE_SURFACE_TEXTURE;
    }

    @Override
    protected int setFrameRate(final int framerate) {
        mFrameRate = framerate;

        if (cameraThreadHandler != null) {
            cameraThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateRateOnCameraThread(framerate);
                }
            });
        }

        return 0;
    }

    @Override
    protected int setResolution(int width, int height) {
        mCaptureWidth = width;
        mCaptureHeight = height;
        restartCam();
        return 0;
    }

    @Override
    protected int setFrontCam(int bFront) {
        mFront = bFront;
        restartCam();
        return 0;
    }

    @Override
    protected int setView(final View view) {
        if (view instanceof TextureView) {
            setRendererView((TextureView) view);
        }

        return 0;
    }

    @Override
    protected int setViewMode(int nMode) {
        mViewMode = nMode;
        return 0;
    }

    @Override
    protected int setViewRotation(int nRotation) {
        return 0;
    }

    @Override
    protected int setCaptureRotation(int nRotation) {
        mDisplayRotation = nRotation;
        return 0;
    }

    @Override
    protected int startPreview() {
        mIsPreview = true;
        return startCamera();
    }

    @Override
    protected int stopPreview() {
        mIsPreview = false;
        return stopCamera();
    }

    @Override
    protected int enableTorch(boolean bEnable) {
        return 0;
    }

    @Override
    protected int takeSnapshot() {
        return 0;
    }

    @Override
    protected int setPowerlineFreq(int nFreq) {
        return 0;
    }

    private int updateRateOnCameraThread(final int framerate) {
        checkIsOnCameraThread();
        if (mCam == null) {
            return 0;
        }

        mFrameRate = framerate;

        Camera.Parameters parms = mCam.getParameters();
        List<int[]> supported = parms.getSupportedPreviewFpsRange();

        for (int[] entry : supported) {
            if ((entry[0] == entry[1]) && entry[0] == mFrameRate * 1000) {
                parms.setPreviewFpsRange(entry[0], entry[1]);
                break;
            }
        }

        int[] realRate = new int[2];
        parms.getPreviewFpsRange(realRate);
        if (realRate[0] == realRate[1]) {
            mFrameRate = realRate[0] / 1000;
        } else {
            mFrameRate = realRate[1] / 2 / 1000;
        }

        try {
            mCam.setParameters(parms);
        } catch (Exception ex) {
            Log.i(TAG, "vcap: update fps -- set camera parameters error with exception\n");
            ex.printStackTrace();
        }
        return 0;
    }

    private void checkIsOnCameraThread() {
        if (cameraThreadHandler == null) {
            Log.e(TAG, "Camera is not initialized - can't check thread.");
        } else if (Thread.currentThread() != cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }

    private boolean maybePostOnCameraThread(Runnable runnable) {
        return cameraThreadHandler != null && isCameraRunning.get()
                && cameraThreadHandler.postAtTime(runnable, this, SystemClock.uptimeMillis());
    }

    // Note that this actually opens the camera, and Camera callbacks run on the
    // thread that calls open(), so this is done on the CameraThread.
    private int createCamOnCameraThread() {
        checkIsOnCameraThread();
        if (!isCameraRunning.get()) {
            Log.e(TAG, "startCaptureOnCameraThread: Camera is stopped");
            return 0;
        }

        Log.i(TAG, "board: " + Build.BOARD);
        Log.i(TAG, "device: " + Build.DEVICE);
        Log.i(TAG, "manufacturer: " + Build.MANUFACTURER);
        Log.i(TAG, "brand: " + Build.BRAND);
        Log.i(TAG, "model: " + Build.MODEL);
        Log.i(TAG, "product: " + Build.PRODUCT);
        Log.i(TAG, "sdk: " + Build.VERSION.SDK_INT);

        int nFacing = (mFront != 0) ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;

        if (mCam != null) {
            // * already created
            return 0;
        }

        // * find camera
        mCamInfo = new Camera.CameraInfo();
        int nCnt = Camera.getNumberOfCameras();
        for (int i = 0; i < nCnt; i++) {
            Camera.getCameraInfo(i, mCamInfo);
            if (mCamInfo.facing == nFacing) {
                mCam = Camera.open(i);
                break;
            }
        }

        // * no camera found ??
        if (mCam == null) {
            Log.i(TAG, "[WARNING] no camera found, try default\n");
            mCam = Camera.open();

            if (mCam == null) {
                Log.i(TAG, "[ERROR] no camera found\n");
                return -1;
            }
        }

        // *
        // * Now set preview size
        // *
        boolean bSizeSet = false;
        Camera.Parameters parms = mCam.getParameters();
        Camera.Size psz = parms.getPreferredPreviewSizeForVideo();

        parms.setPreviewSize(psz.width, psz.height);
        mCameraWidth = psz.width;
        mCameraHeight = psz.height;

        // *
        // * Now set fps
        // *
        List<int[]> supported = parms.getSupportedPreviewFpsRange();

        for (int[] entry : supported) {
            if ((entry[0] == entry[1]) && entry[0] == mFrameRate * 1000) {
                parms.setPreviewFpsRange(entry[0], entry[1]);
                break;
            }
        }

        int[] realRate = new int[2];
        parms.getPreviewFpsRange(realRate);
        if (realRate[0] == realRate[1]) {
            mFrameRate = realRate[0] / 1000;
        } else {
            mFrameRate = realRate[1] / 2 / 1000;
        }

        // *
        // * focus mode
        // *
        boolean bFocusModeSet = false;
        for (String mode : parms.getSupportedFocusModes()) {
            if (mode.compareTo(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == 0) {
                try {
                    parms.setFocusMode(mode);
                    bFocusModeSet = true;
                    break;
                } catch (Exception ex) {
                    Log.i(TAG, "[WARNING] vcap: set focus mode error (stack trace followed)!!!\n");
                    ex.printStackTrace();
                }
            }
        }
        if (!bFocusModeSet) {
            Log.i(TAG, "[WARNING] vcap: focus mode left unset !!\n");
        }

        // *
        // * Now try to set parm
        // *
        try {
            mCam.setParameters(parms);
        } catch (Exception ex) {
            Log.i(TAG, "vcap: set camera parameters error with exception\n");
            ex.printStackTrace();
        }

        Camera.Parameters actualParm = mCam.getParameters();
        mCameraWidth = actualParm.getPreviewSize().width;
        mCameraHeight = actualParm.getPreviewSize().height;
        Log.i(TAG, "[WARNING] vcap: focus mode " + actualParm.getFocusMode());

        int result;
        if (mCamInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (mCamInfo.orientation + mDisplayRotation) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (mCamInfo.orientation - mDisplayRotation + 360) % 360;
        }
        mCam.setDisplayOrientation(result);
        mImageRotation = result;

        // reset context here
        mDummyContext.makeCurrent();
        Log.d(TAG, "ImageRotation:" + mImageRotation);
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

        releaseCaptureSurface();

        if (mFURenderer != null) {
            mFURenderer.onCameraChange(mCamInfo.facing, mCamInfo.orientation);
        }
        return 0;
    }

    private int startCamOnCameraThread() {
        checkIsOnCameraThread();
        if (!isCameraRunning.get() || mCam == null) {
            Log.e(TAG, "startPreviewOnCameraThread: Camera is stopped");
            return 0;
        }

        // * mCam.setDisplayOrientation(90);
        if (mInputSurfaceTexture == null) {
            Log.e(TAG, "mInputSurfaceTexture == null");
            return -1;
        }

        try {
            mCam.setPreviewTexture(mInputSurfaceTexture);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (previewCallbackBuffer == null) {
            previewCallbackBuffer = new byte[PREVIEW_BUFFER_COUNT][mCameraWidth * mCameraHeight * 3 / 2];
        }
        mCam.setPreviewCallbackWithBuffer(this);
        for (int i = 0; i < PREVIEW_BUFFER_COUNT; i++) {
            mCam.addCallbackBuffer(previewCallbackBuffer[i]);
        }
        mCam.startPreview();
        Log.e(TAG, "startPreview success");

        return 0;
    }

    private int stopCaptureOnCameraThread(boolean stopHandler) {
        checkIsOnCameraThread();
        Log.d(TAG, "stopCaptureOnCameraThread");

        if (stopHandler) {
            // Clear the cameraThreadHandler first, in case stopPreview or
            // other driver code deadlocks. Deadlock in
            // android.hardware.Camera._stopPreview(Native Method) has
            // been observed on Nexus 5 (hammerhead), OS version LMY48I.
            // The camera might post another one or two preview frames
            // before stopped, so we have to check |isCameraRunning|.
            // Remove all pending Runnables posted from |this|.
            isCameraRunning.set(false);
            cameraThreadHandler.removeCallbacksAndMessages(this /* token */);
        }

        if (mCam != null) {
            mCam.stopPreview();
        }
        return 0;
    }

    private int restartCam() {
        synchronized (pendingCameraRestartLock) {
            if (pendingCameraRestart) {
                // Do not handle multiple camera switch request to avoid blocking
                // camera thread by handling too many switch request from a queue.
                Log.w(TAG, "Ignoring camera switch request.");
                return 0;
            }
            pendingCameraRestart = true;
        }

        final boolean didPost = maybePostOnCameraThread(new Runnable() {
            @Override
            public void run() {
                stopCaptureOnCameraThread(false);
                releaseCam();
                createCamOnCameraThread();
                startCamOnCameraThread();
                synchronized (pendingCameraRestartLock) {
                    pendingCameraRestart = false;
                }
            }
        });

        if (!didPost) {
            synchronized (pendingCameraRestartLock) {
                pendingCameraRestart = false;
            }
        }

        return 0;
    }

    private int releaseCam() {
        // * release cam
        if (mCam != null) {
            mCam.setPreviewCallbackWithBuffer(null);
            mCam.release();
            mCam = null;
        }

        // * release cam info
        mCamInfo = null;
        return 0;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        if (cameraThreadHandler != null) {
            cameraThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    releasePreviewSurface();
                }
            });
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (cameraThreadHandler != null) {
            final CountDownLatch barrier = new CountDownLatch(1);
            cameraThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    releasePreviewSurface();
                    barrier.countDown();
                }
            });
            try {
                barrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {

    }

    public int setRendererView(TextureView view) {
        if (cameraThreadHandler == null) {
            doSetRendererView(view);
        } else {
            final CountDownLatch barrier = new CountDownLatch(1);
            final TextureView temp = view;
            cameraThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    doSetRendererView(temp);
                    barrier.countDown();
                }
            });
            try {
                barrier.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return 0;
    }

    private void doSetRendererView(TextureView temp) {
        if (mTextureView != null) {
            if (mTextureView.getSurfaceTextureListener().equals(FUVideoCaptureFromCamera2.this)) {
                mTextureView.setSurfaceTextureListener(null);
            }
            releasePreviewSurface();
        }

        mTextureView = temp;
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(FUVideoCaptureFromCamera2.this);
        }
    }

    private void attachTextureView() {
        if (previewEglBase.hasSurface()) {
            return;
        }

        if (!mTextureView.isAvailable()) {
            return;
        }

        mViewWidth = mTextureView.getWidth();
        mViewHeight = mTextureView.getHeight();
        try {
            previewEglBase.createSurface(mTextureView.getSurfaceTexture());
        } catch (RuntimeException e) {
            e.printStackTrace();
            releasePreviewSurface();
            mViewWidth = 0;
            mViewHeight = 0;
        }
    }

    private void drawToPreview(int textureId, int width, int height, float[] texMatrix) {
        if (previewEglBase == null) {
            previewEglBase = EglBase.create(mDummyContext.getEglBaseContext(), EglBase.CONFIG_RGBA);
        }

        if (mTextureView != null) {
            attachTextureView();
        }

        if (!previewEglBase.hasSurface()) {
            return;
        }

        if (previewDrawer == null) {
            previewDrawer = new GlRectDrawer();
        }

        try {
            previewEglBase.makeCurrent();

            int scaleWidth = mViewWidth;
            int scaleHeight = mViewHeight;
            System.arraycopy(texMatrix, 0, mPreviewMatrix, 0, 16);
            if (mViewMode == 0) {
                if (mViewHeight * width <= mViewWidth * height) {
                    scaleWidth = mViewHeight * width / height;
                } else {
                    scaleHeight = mViewWidth * height / width;
                }
            } else if (mViewMode == 1) {
                if (mViewHeight * width <= mViewWidth * height) {
                    scaleHeight = mViewWidth * height / width;
                } else {
                    scaleWidth = mViewHeight * width / height;
                }
                float fWidthScale = (float) mViewWidth / (float) scaleWidth;
                float fHeightScale = (float) mViewHeight / (float) scaleHeight;
                Matrix.scaleM(mPreviewMatrix, 0, fWidthScale, fHeightScale, 1.0f);
                Matrix.translateM(mPreviewMatrix, 0, (1.0f - fWidthScale) / 2.0f, (1.0f - fHeightScale) / 2.0f, 1.0f);

                scaleWidth = mViewWidth;
                scaleHeight = mViewHeight;
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            previewDrawer.drawRgb(textureId, mPreviewMatrix, (int) width, (int) height,
                    (mViewWidth - scaleWidth) / 2,
                    (mViewHeight - scaleHeight) / 2,
                    scaleWidth, scaleHeight);
            previewEglBase.swapBuffers();
            previewEglBase.detachCurrent();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void drawToCapture(int textureId, int width, int height, float[] texMatrix, long timestamp_ns) {
        if (captureEglBase == null) {
            captureEglBase = EglBase.create(mDummyContext.getEglBaseContext(), EglBase.CONFIG_RECORDABLE);
        }

        if (!captureEglBase.hasSurface()) {
            SurfaceTexture temp = mClient.getSurfaceTexture();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
                temp.setDefaultBufferSize(mCaptureWidth, mCaptureHeight);
            }
            try {
                captureEglBase.createSurface(temp);
                captureEglBase.makeCurrent();
                captureDrawer = new GlRectDrawer();
            } catch (RuntimeException e) {
                e.printStackTrace();
                // Clean up before rethrowing the exception.
                captureEglBase.releaseSurface();
                return;
            }
        }

        try {
            captureEglBase.makeCurrent();

            // support crop only
            int scaleWidth = mCaptureWidth;
            int scaleHeight = mCaptureHeight;
            System.arraycopy(texMatrix, 0, mCaptureMatrix, 0, 16);
            if (mCaptureHeight * width <= mCaptureWidth * height) {
                scaleHeight = mCaptureWidth * height / width;
            } else {
                scaleWidth = mCaptureHeight * width / height;
            }
            float fWidthScale = (float) mCaptureWidth / (float) scaleWidth;
            float fHeightScale = (float) mCaptureHeight / (float) scaleHeight;
            Matrix.scaleM(mCaptureMatrix, 0, fWidthScale, fHeightScale, 1.0f);
            Matrix.translateM(mCaptureMatrix, 0, (1.0f - fWidthScale) / 2.0f, (1.0f - fHeightScale) / 2.0f, 1.0f);

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            captureDrawer.drawRgb(textureId, mCaptureMatrix, width, height,
                    0, 0,
                    mCaptureWidth, mCaptureHeight);
            if (mIsEgl14) {
                ((EglBase14) captureEglBase).swapBuffers(timestamp_ns);
            } else {
                captureEglBase.swapBuffers();
            }

            captureEglBase.detachCurrent();
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void releasePreviewSurface() {
        if (previewEglBase == null) {
            return;
        }

        if (previewEglBase.hasSurface()) {
            previewEglBase.makeCurrent();

            if (previewDrawer != null) {
                previewDrawer = null;
            }

            previewEglBase.releaseSurface();
            previewEglBase.detachCurrent();
        }

        previewEglBase.release();
        previewEglBase = null;
    }

    private void releaseCaptureSurface() {
        if (captureEglBase == null) {
            return;
        }

        if (captureEglBase.hasSurface()) {
            captureEglBase.makeCurrent();

            if (captureDrawer != null) {
                captureDrawer = null;
            }

            captureEglBase.releaseSurface();
            captureEglBase.detachCurrent();
        }
    }

    //    @Override
//    public void doFrame(long frameTimeNanos) {
//        if (!mIsRunning) {
//            return ;
//        }
//        Choreographer.getInstance().postFrameCallback(this);
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mDummyContext.makeCurrent();
        surfaceTexture.updateTexImage();
        long timestamp = surfaceTexture.getTimestamp();
        surfaceTexture.getTransformMatrix(mInputMatrix);

        // do preprocessing here such as camera 360 sdk

        // 1. correct DisplayOrientation
        int width = mCameraWidth;
        int height = mCameraHeight;
        if (mImageRotation == 90 || mImageRotation == 270) {
            int temp = width;
            width = height;
            height = temp;
        }

        int fexId = 0;
        if (mCameraNV21Byte != null) {
            fexId = mFURenderer.onDrawFrameDualInput(mCameraNV21Byte, mInputTextureId, mCameraWidth, mCameraHeight);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        createFBO(width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]);
        mDummyDrawer.drawRgb(fexId, mInputMatrix, width, height, 0, 0, width, height);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // 2. draw to capture
        if (mIsCapture) {
            drawToCapture(fboTex[0], width, height, mIdentityMatrix, timestamp);
        }

        // 3. draw to preview
        if (mIsPreview) {
            drawToPreview(fboTex[0], width, height, mIdentityMatrix);
        }
    }

    private static final int PREVIEW_BUFFER_COUNT = 3;
    private byte[][] previewCallbackBuffer;
    private byte[] mCameraNV21Byte;

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mCameraNV21Byte = data;
        mCam.addCallbackBuffer(data);
    }

    private int fboId[];
    private int fboTex[];

    private void createFBO(int width, int height) {
        if (fboTex == null) {
            fboId = new int[1];
            fboTex = new int[1];

//generate fbo id
            GLES20.glGenFramebuffers(1, fboId, 0);
//generate texture
            GLES20.glGenTextures(1, fboTex, 0);

//Bind Frame buffer
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]);
//Bind texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0]);
//Define texture parameters
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
//Attach texture FBO color attachment
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[0], 0);
//we are done, reset
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    private void deleteFBO() {
        if (fboId == null || fboTex == null) {
            return;
        }
        GLES20.glDeleteFramebuffers(1, fboId, 0);
        GLES20.glDeleteTextures(1, fboTex, 0);
        fboId = null;
        fboTex = null;
    }
}
