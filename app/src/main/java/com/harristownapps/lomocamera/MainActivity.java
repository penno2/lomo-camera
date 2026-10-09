package com.harristownapps.lomocamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A deliberately tiny, single-purpose camera: live Lomo view, shutter, focus, zoom,
 * flash and camera switch. No network permission, accounts, analytics or filter picker.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "LomoCamera";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final long PRECAPTURE_TIMEOUT_MS = 1800L;
    private static final String PREFERENCES_NAME = "lomo_camera";
    private static final String PREF_STRENGTH = "lomo_strength";

    private static final int CAPTURE_STATE_PREVIEW = 0;
    private static final int CAPTURE_STATE_WAITING_PRECAPTURE = 1;
    private static final int CAPTURE_STATE_WAITING_NON_PRECAPTURE = 2;

    private enum FlashMode { OFF, AUTO, ON }

    private AutoFitTextureView textureView;
    private FocusIndicatorView focusIndicator;
    private ShutterButton shutterButton;
    private ImageButton flashButton;
    private ImageButton switchButton;
    private FrameLayout rootView;
    private TextView savedIndicator;
    private TextView strengthValue;
    private volatile int lomoStrengthPercent = 100;
    private volatile int captureStrengthPercent = 100;

    private LomoFilter lomoFilter;
    private ScaleGestureDetector scaleGestureDetector;
    private OrientationEventListener orientationListener;
    private int deviceOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

    private CameraManager cameraManager;
    private volatile CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCharacteristics cameraCharacteristics;
    private String cameraId;
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private Size previewSize;
    private Size captureSize;
    private ImageReader imageReader;
    private Surface previewSurface;
    private boolean flashAvailable;
    private FlashMode flashMode = FlashMode.OFF;
    private FlashMode captureFlashMode = FlashMode.OFF;
    private Range<Float> zoomRange = new Range<>(1f, 1f);
    private float zoomRatio = 1f;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final ExecutorService photoExecutor = Executors.newSingleThreadExecutor();
    // CameraDevice state callbacks must outlive cameraThread shutdown; otherwise Android can
    // try to deliver onClosed() to a dead Handler when the Activity is paused.
    private final ExecutorService cameraStateExecutor = Executors.newSingleThreadExecutor();
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private volatile boolean cameraOpening;
    private boolean processingPhoto;
    private int captureState = CAPTURE_STATE_PREVIEW;
    private Runnable precaptureTimeout;

    private float downX;
    private float downY;
    private boolean tapCandidate;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            try {
                createCameraPreviewSession();
            } finally {
                completeCameraOpenAttempt();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            completeCameraOpenAttempt();
            camera.close();
            if (cameraDevice == camera) cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            completeCameraOpenAttempt();
            camera.close();
            if (cameraDevice == camera) cameraDevice = null;
            showToast("Camera error " + error);
        }

        @Override
        public void onClosed(CameraDevice camera) {
            if (cameraDevice == camera) cameraDevice = null;
        }
    };

    /**
     * The preview callback doubles as the small capture state machine used for flash.
     * Camera2 flash AE is a two-stage process: request AE precapture, wait for the
     * precapture sequence to finish, then issue the actual JPEG request.
     */
    private final CameraCaptureSession.CaptureCallback cameraCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request,
                                               TotalCaptureResult result) {
                    processCaptureResult(result);
                }
            };

    private final ImageReader.OnImageAvailableListener imageAvailableListener = reader -> {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
            photoExecutor.execute(() -> processAndSavePhoto(jpeg));
        } catch (Exception e) {
            Log.e(TAG, "Unable to read captured image", e);
            runOnUiThread(() -> finishPhotoProcessing("Capture failed"));
        } finally {
            if (image != null) image.close();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hideSystemBars();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        lomoFilter = new LomoFilter(this);
        lomoStrengthPercent = Math.max(0, Math.min(200,
                getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).getInt(PREF_STRENGTH, 100)));
        buildUi();
        lomoFilter.applyToPreview(textureView);
        lomoFilter.setPreviewStrength(lomoStrengthPercent);
        setUpGestures();
        setUpOrientationListener();
    }

    private void buildUi() {
        rootView = new FrameLayout(this);
        FrameLayout root = rootView;
        root.setBackgroundColor(Color.BLACK);

        textureView = new AutoFitTextureView(this);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(textureView, previewParams);

        focusIndicator = new FocusIndicatorView(this);
        root.addView(focusIndicator, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout topControls = new LinearLayout(this);
        topControls.setOrientation(LinearLayout.HORIZONTAL);
        topControls.setGravity(Gravity.CENTER_VERTICAL);

        flashButton = makeIconControlButton(R.drawable.ic_flash_off, "Flash off");
        flashButton.setOnClickListener(v -> cycleFlashMode());
        topControls.addView(flashButton, squareLayout(58));

        switchButton = makeIconControlButton(R.drawable.ic_camera_switch, "Switch camera");
        switchButton.setOnClickListener(v -> switchCamera());
        LinearLayout.LayoutParams switchLp = squareLayout(58);
        switchLp.leftMargin = dp(10);
        topControls.addView(switchButton, switchLp);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        topLp.setMargins(dp(18), dp(22), dp(18), 0);
        root.addView(topControls, topLp);

        shutterButton = new ShutterButton(this);
        shutterButton.setOnClickListener(v -> takePicture());
        FrameLayout.LayoutParams shutterLp = new FrameLayout.LayoutParams(dp(88), dp(88),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        shutterLp.bottomMargin = dp(30);
        root.addView(shutterButton, shutterLp);

        // One compact control for the entire calibrated colour treatment.
        // It sits just above the shutter and doesn't interfere with tap-focus/zoom.
        LinearLayout strengthBar = new LinearLayout(this);
        strengthBar.setOrientation(LinearLayout.HORIZONTAL);
        strengthBar.setGravity(Gravity.CENTER_VERTICAL);
        strengthBar.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable strengthBackground = new GradientDrawable();
        strengthBackground.setColor(0xB0000000);
        strengthBackground.setCornerRadius(dp(22));
        strengthBackground.setStroke(dp(1), 0x55FFFFFF);
        strengthBar.setBackground(strengthBackground);

        TextView strengthLabel = new TextView(this);
        strengthLabel.setText("LOMO");
        strengthLabel.setTextColor(Color.WHITE);
        strengthLabel.setTextSize(12);
        strengthLabel.setGravity(Gravity.CENTER_VERTICAL);
        strengthBar.addView(strengthLabel, new LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(42)));

        SeekBar strengthSlider = new SeekBar(this);
        strengthSlider.setMax(200);
        strengthSlider.setProgress(lomoStrengthPercent);
        strengthSlider.setContentDescription("Lomo strength, 0 to 200 percent");
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sliderLp.leftMargin = dp(7);
        sliderLp.rightMargin = dp(7);
        strengthBar.addView(strengthSlider, sliderLp);

        strengthValue = new TextView(this);
        strengthValue.setText(lomoStrengthPercent + "%");
        strengthValue.setTextColor(Color.WHITE);
        strengthValue.setTextSize(13);
        strengthValue.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        strengthBar.addView(strengthValue, new LinearLayout.LayoutParams(dp(45), dp(42)));

        strengthSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                lomoStrengthPercent = progress;
                strengthValue.setText(progress + "%");
                lomoFilter.setPreviewStrength(progress);
                textureView.invalidate();
                if (fromUser) {
                    getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE).edit()
                            .putInt(PREF_STRENGTH, progress).apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.setMargins(dp(18), 0, dp(18), dp(132));
        root.addView(strengthBar, barLp);

        setContentView(root);
    }

    private ImageButton makeIconControlButton(int iconRes, String contentDescription) {
        ImageButton v = new ImageButton(this);
        v.setImageResource(iconRes);
        v.setColorFilter(Color.WHITE);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        v.setContentDescription(contentDescription);
        v.setClickable(true);
        v.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        v.setBackground(bg);
        return v;
    }

    private LinearLayout.LayoutParams squareLayout(int dp) {
        return new LinearLayout.LayoutParams(dp(dp), dp(dp));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideSystemBars() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            //controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.hide(WindowInsets.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void setUpGestures() {
        scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        tapCandidate = false;
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        setZoom(zoomRatio * detector.getScaleFactor());
                        return true;
                    }
                });

        textureView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    tapCandidate = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(12)) {
                        tapCandidate = false;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (tapCandidate && !scaleGestureDetector.isInProgress()) {
                        focusIndicator.showAt(event.getX(), event.getY());
                        focusAt(event.getX(), event.getY());
                    }
                    tapCandidate = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    tapCandidate = false;
                    break;
            }
            return true;
        });
    }

    private void setUpOrientationListener() {
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    deviceOrientation = orientation;
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        startCameraThread();
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        if (orientationListener != null) orientationListener.disable();
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        photoExecutor.shutdown();
        cameraStateExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (previewSize != null) {
            updateTextureAspectRatio();
            configureTransform(textureView.getWidth(), textureView.getHeight());
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("LomoCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        if (cameraHandler != null) cameraHandler.removeCallbacksAndMessages(null);
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void completeCameraOpenAttempt() {
        if (cameraOpening) {
            cameraOpening = false;
            cameraOpenCloseLock.release();
        }
    }

    private void openCamera(int width, int height) {
        if (cameraDevice != null || cameraOpening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        boolean lockAcquired = false;
        try {
            setUpCameraOutputs(width, height);
            configureTransform(width, height);
            if (cameraId == null) {
                showToast("No suitable camera found");
                return;
            }
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Timed out waiting to open camera");
            }
            lockAcquired = true;
            cameraOpening = true;
            cameraManager.openCamera(cameraId, cameraStateExecutor, cameraStateCallback);
            // The StateCallback owns the semaphore permit until the open attempt completes.
            lockAcquired = false;
        } catch (CameraAccessException e) {
            cameraOpening = false;
            Log.e(TAG, "Cannot open camera", e);
            showToast("Cannot open camera");
        } catch (InterruptedException e) {
            cameraOpening = false;
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            cameraOpening = false;
            Log.e(TAG, "Camera open failed", e);
            showToast("Camera unavailable");
        } finally {
            if (lockAcquired) cameraOpenCloseLock.release();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable()) openCamera(textureView.getWidth(), textureView.getHeight());
            } else {
                showToast("Camera permission is required");
            }
        }
    }

    private void setUpCameraOutputs(int width, int height) throws CameraAccessException {
        cameraId = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != lensFacing) continue;

            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) continue;

            Size[] jpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            if (jpegSizes == null || jpegSizes.length == 0) continue;
            captureSize = Collections.max(Arrays.asList(jpegSizes), new CompareSizesByArea());

            if (imageReader != null) imageReader.close();
            imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(imageAvailableListener, cameraHandler);

            int displayRotation = getDisplay().getRotation();
            Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) sensorOrientation = 0;
            boolean swappedDimensions = false;
            switch (displayRotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if (sensorOrientation == 90 || sensorOrientation == 270) swappedDimensions = true;
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if (sensorOrientation == 0 || sensorOrientation == 180) swappedDimensions = true;
                    break;
            }

            android.graphics.Point displaySize = new android.graphics.Point();
            getDisplay().getRealSize(displaySize);
            int rotatedPreviewWidth = width;
            int rotatedPreviewHeight = height;
            int maxPreviewWidth = displaySize.x;
            int maxPreviewHeight = displaySize.y;
            if (swappedDimensions) {
                rotatedPreviewWidth = height;
                rotatedPreviewHeight = width;
                maxPreviewWidth = displaySize.y;
                maxPreviewHeight = displaySize.x;
            }
            maxPreviewWidth = Math.min(maxPreviewWidth, MAX_PREVIEW_WIDTH);
            maxPreviewHeight = Math.min(maxPreviewHeight, MAX_PREVIEW_HEIGHT);

            previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture.class),
                    rotatedPreviewWidth,
                    rotatedPreviewHeight,
                    maxPreviewWidth,
                    maxPreviewHeight,
                    captureSize);

            cameraId = id;
            cameraCharacteristics = c;
            flashAvailable = Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) {
                zoomRange = zr;
            } else {
                Float maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                zoomRange = new Range<>(1f, maxDigital == null ? 1f : Math.max(1f, maxDigital));
            }
            zoomRatio = clamp(1f, zoomRange.getLower(), zoomRange.getUpper());
            runOnUiThread(() -> {
                updateTextureAspectRatio();
                updateFlashButton();
            });
            return;
        }
    }

    private void updateTextureAspectRatio() {
        if (previewSize == null) return;
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
        } else {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || previewSize == null || imageReader == null || cameraDevice == null) return;
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            releasePreviewSurface();
            previewSurface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyZoom(previewRequestBuilder);
            applyFlash(previewRequestBuilder);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                                        cameraCaptureCallback, cameraHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Unable to start preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            showToast("Camera preview failed");
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to create preview", e);
        }
    }

    private void releasePreviewSurface() {
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void closeCamera() {
        cancelPrecaptureTimeout();
        captureState = CAPTURE_STATE_PREVIEW;
        try {
            cameraOpenCloseLock.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            previewRequestBuilder = null;
            releasePreviewSurface();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cameraOpening = false;
            cameraOpenCloseLock.release();
        }
    }

    private void switchCamera() {
        if (processingPhoto) return;
        lensFacing = lensFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        closeCamera();
        if (textureView.isAvailable()) openCamera(textureView.getWidth(), textureView.getHeight());
    }

    private void cycleFlashMode() {
        if (processingPhoto || !flashAvailable) return;
        switch (flashMode) {
            case OFF: flashMode = FlashMode.AUTO; break;
            case AUTO: flashMode = FlashMode.ON; break;
            case ON: flashMode = FlashMode.OFF; break;
        }
        updateFlashButton();
        if (previewRequestBuilder != null && captureSession != null) {
            try {
                applyFlash(previewRequestBuilder);
                captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                        cameraCaptureCallback, cameraHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Unable to update flash", e);
            }
        }
    }

    private void updateFlashButton() {
        if (flashButton == null) return;
        flashButton.setVisibility(flashAvailable ? View.VISIBLE : View.INVISIBLE);
        if (!flashAvailable) return;
        switch (flashMode) {
            case OFF:
                flashButton.setImageResource(R.drawable.ic_flash_off);
                flashButton.setContentDescription("Flash off");
                break;
            case AUTO:
                flashButton.setImageResource(R.drawable.ic_flash_auto);
                flashButton.setContentDescription("Flash automatic");
                break;
            case ON:
                flashButton.setImageResource(R.drawable.ic_flash_on);
                flashButton.setContentDescription("Flash on");
                break;
        }
    }

    /** Configure the preview/AE pipeline for the selected flash mode. */
    private void applyFlash(CaptureRequest.Builder builder) {
        if (!flashAvailable) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            return;
        }
        switch (flashMode) {
            case OFF:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case ON:
                // Keep AE aware that the final shot will use flash. The actual still request
                // uses FLASH_MODE_SINGLE so ON really means "fire", independent of AE's choice.
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
        }
    }

    private void setZoom(float value) {
        float next = clamp(value, zoomRange.getLower(), zoomRange.getUpper());
        if (Math.abs(next - zoomRatio) < 0.001f) return;
        zoomRatio = next;
        if (previewRequestBuilder == null || captureSession == null) return;
        try {
            applyZoom(previewRequestBuilder);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                    cameraCaptureCallback, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to zoom", e);
        }
    }

    private void applyZoom(CaptureRequest.Builder builder) {
        if (cameraCharacteristics == null) return;
        Range<Float> zr = cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zr != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO,
                    clamp(zoomRatio, zr.getLower(), zr.getUpper()));
        }
    }

    private void focusAt(float x, float y) {
        if (previewRequestBuilder == null || captureSession == null || cameraCharacteristics == null) return;
        Rect active = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer maxAf = cameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = cameraCharacteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        if (active == null || ((maxAf == null || maxAf == 0) && (maxAe == null || maxAe == 0))) return;

        float dx = clamp(x / Math.max(1f, textureView.getWidth()), 0f, 1f);
        float dy = clamp(y / Math.max(1f, textureView.getHeight()), 0f, 1f);
        int rotation = computeRelativeRotation(cameraCharacteristics, surfaceRotationDegrees());
        float sx;
        float sy;
        switch (rotation) {
            case 90:
                sx = dy;
                sy = 1f - dx;
                break;
            case 180:
                sx = 1f - dx;
                sy = 1f - dy;
                break;
            case 270:
                sx = 1f - dy;
                sy = dx;
                break;
            default:
                sx = dx;
                sy = dy;
                break;
        }

        int cropW = Math.round(active.width() / Math.max(1f, zoomRatio));
        int cropH = Math.round(active.height() / Math.max(1f, zoomRatio));
        int cropLeft = active.centerX() - cropW / 2;
        int cropTop = active.centerY() - cropH / 2;
        Rect crop = new Rect(cropLeft, cropTop, cropLeft + cropW, cropTop + cropH);

        int cx = crop.left + Math.round(sx * crop.width());
        int cy = crop.top + Math.round(sy * crop.height());
        int half = Math.max(24, Math.min(crop.width(), crop.height()) / 16);
        Rect metering = new Rect(
                clamp(cx - half, crop.left, crop.right - 1),
                clamp(cy - half, crop.top, crop.bottom - 1),
                clamp(cx + half, crop.left + 1, crop.right),
                clamp(cy + half, crop.top + 1, crop.bottom));
        MeteringRectangle region = new MeteringRectangle(metering,
                MeteringRectangle.METERING_WEIGHT_MAX);

        try {
            if (maxAf != null && maxAf > 0) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                        new MeteringRectangle[]{region});
            }
            if (maxAe != null && maxAe > 0) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                        new MeteringRectangle[]{region});
            }
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(previewRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession session,
                                                       CaptureRequest request,
                                                       TotalCaptureResult result) {
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(previewRequestBuilder.build(),
                                        cameraCaptureCallback, cameraHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Unable to resume autofocus", e);
                            }
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Tap focus failed", e);
        }
    }

    private void takePicture() {
        if (processingPhoto || cameraDevice == null || captureSession == null || imageReader == null) return;
        processingPhoto = true;
        shutterButton.setEnabled(false);
        captureFlashMode = flashMode;
        // Snapshot strength at the shutter, even if the slider moves during JPEG processing.
        captureStrengthPercent = lomoStrengthPercent;

        if (!flashAvailable || captureFlashMode == FlashMode.OFF) {
            captureStillPicture();
        } else {
            startPrecaptureSequence();
        }
    }

    /** Trigger AE's flash precapture metering before the real still request. */
    private void startPrecaptureSequence() {
        if (captureSession == null || previewRequestBuilder == null || cameraHandler == null) {
            finishPhotoProcessing("Capture failed");
            return;
        }
        try {
            captureState = CAPTURE_STATE_WAITING_PRECAPTURE;

            if (captureFlashMode == FlashMode.AUTO) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            } else {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            }
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            Log.i(TAG, "Starting AE precapture for flash=" + captureFlashMode);
            captureSession.capture(previewRequestBuilder.build(), cameraCaptureCallback, cameraHandler);

            // START is a one-shot trigger; don't leave it asserted on later requests.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);

            cancelPrecaptureTimeout();
            precaptureTimeout = () -> {
                if (processingPhoto && captureState != CAPTURE_STATE_PREVIEW) {
                    Log.w(TAG, "AE precapture timed out; taking photo anyway");
                    captureState = CAPTURE_STATE_PREVIEW;
                    captureStillPicture();
                }
            };
            cameraHandler.postDelayed(precaptureTimeout, PRECAPTURE_TIMEOUT_MS);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "Unable to start flash precapture", e);
            captureState = CAPTURE_STATE_PREVIEW;
            captureStillPicture();
        }
    }

    private void processCaptureResult(CaptureResult result) {
        if (!processingPhoto || captureState == CAPTURE_STATE_PREVIEW) return;

        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        if (captureState == CAPTURE_STATE_WAITING_PRECAPTURE) {
            if (aeState == null ||
                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == CaptureResult.CONTROL_AE_STATE_LOCKED) {
                captureState = CAPTURE_STATE_WAITING_NON_PRECAPTURE;
            }
            return;
        }

        if (captureState == CAPTURE_STATE_WAITING_NON_PRECAPTURE &&
                (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE)) {
            captureState = CAPTURE_STATE_PREVIEW;
            captureStillPicture();
        }
    }

    private void captureStillPicture() {
        cancelPrecaptureTimeout();
        if (cameraDevice == null || captureSession == null || imageReader == null) {
            runOnUiThread(() -> finishPhotoProcessing("Capture failed"));
            return;
        }

        try {
            CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT,
                    CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyZoom(captureBuilder);
            applyStillFlash(captureBuilder, captureFlashMode);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);

            captureSession.stopRepeating();
            captureSession.capture(captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession session,
                                                       CaptureRequest request,
                                                       TotalCaptureResult result) {
                            Integer flashState = result.get(CaptureResult.FLASH_STATE);
                            Log.i(TAG, "Still capture flash=" + captureFlashMode +
                                    " result=" + flashStateName(flashState));
                            resumePreview();
                        }

                        @Override
                        public void onCaptureFailed(CameraCaptureSession session,
                                                    CaptureRequest request,
                                                    android.hardware.camera2.CaptureFailure failure) {
                            Log.e(TAG, "Still capture request failed: reason=" + failure.getReason());
                            resumePreview();
                            runOnUiThread(() -> finishPhotoProcessing("Capture failed"));
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Still capture failed", e);
            resumePreview();
            runOnUiThread(() -> finishPhotoProcessing("Capture failed"));
        }
    }

    private void applyStillFlash(CaptureRequest.Builder builder, FlashMode mode) {
        if (!flashAvailable || mode == FlashMode.OFF) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            return;
        }

        if (mode == FlashMode.AUTO) {
            // AE decides whether flash is necessary, after the precapture pass above.
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        } else {
            // Forced flash: SINGLE means physically fire for this still, regardless of
            // whether AE independently thinks a flash is required.
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
        }
    }

    private void cancelPrecaptureTimeout() {
        if (precaptureTimeout != null && cameraHandler != null) {
            cameraHandler.removeCallbacks(precaptureTimeout);
        }
        precaptureTimeout = null;
    }

    private static String flashStateName(Integer state) {
        if (state == null) return "UNKNOWN";
        switch (state) {
            case CaptureResult.FLASH_STATE_UNAVAILABLE: return "UNAVAILABLE";
            case CaptureResult.FLASH_STATE_CHARGING: return "CHARGING";
            case CaptureResult.FLASH_STATE_READY: return "READY";
            case CaptureResult.FLASH_STATE_FIRED: return "FIRED";
            case CaptureResult.FLASH_STATE_PARTIAL: return "PARTIAL";
            default: return Integer.toString(state);
        }
    }

    private void resumePreview() {
        cancelPrecaptureTimeout();
        captureState = CAPTURE_STATE_PREVIEW;
        if (captureSession == null || previewRequestBuilder == null) return;
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
            applyZoom(previewRequestBuilder);
            applyFlash(previewRequestBuilder);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                    cameraCaptureCallback, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Unable to resume preview", e);
        }
    }

    private int getJpegOrientation() {
        if (cameraCharacteristics == null) return 0;
        int orientation = deviceOrientation;
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
            orientation = surfaceRotationDegrees();
        }
        Integer sensor = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (sensor == null) sensor = 0;
        orientation = (orientation + 45) / 90 * 90;
        Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            orientation = -orientation;
        }
        return (sensor + orientation + 360) % 360;
    }

    private void processAndSavePhoto(byte[] jpeg) {
        Bitmap bitmap = null;
        Bitmap oriented = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;
            bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            if (bitmap == null) throw new IOException("Could not decode JPEG");

            int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
            try {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
                exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException ignored) {
            }

            oriented = orientBitmap(bitmap, exifOrientation);
            if (oriented != bitmap) {
                bitmap.recycle();
                bitmap = null;
            }
            if (!oriented.isMutable()) {
                Bitmap mutable = oriented.copy(Bitmap.Config.ARGB_8888, true);
                if (mutable == null) throw new IOException("Could not create mutable bitmap");
                if (oriented != bitmap) oriented.recycle();
                oriented = mutable;
            }

            lomoFilter.applyToBitmap(oriented, captureStrengthPercent);
            Uri uri = saveBitmap(oriented);
            if (uri == null) throw new IOException("MediaStore insert failed");
            runOnUiThread(() -> finishPhotoProcessing("Saved to DCIM/Lomo"));
        } catch (Exception e) {
            Log.e(TAG, "Photo processing failed", e);
            runOnUiThread(() -> finishPhotoProcessing("Could not save photo"));
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (oriented != null && !oriented.isRecycled()) oriented.recycle();
        }
    }

    private Bitmap orientBitmap(Bitmap source, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.setRotate(180);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.setRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.setRotate(-90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.setRotate(-90);
                break;
            default:
                return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
    }

    private Uri saveBitmap(Bitmap bitmap) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Lomo_" + stamp + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Lomo");
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        boolean ok = false;
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Unable to open output stream");
            ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 96, out);
            if (!ok) throw new IOException("JPEG compression failed");
        } finally {
            if (!ok) resolver.delete(uri, null, null);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    private void finishPhotoProcessing(String message) {
        cancelPrecaptureTimeout();
        captureState = CAPTURE_STATE_PREVIEW;
        processingPhoto = false;
        if (shutterButton != null) shutterButton.setEnabled(true);
        if ("Saved to DCIM/Lomo".equals(message)) {
            showSavedIndicator();
        } else {
            showToast(message);
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        int rotation = getDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private int surfaceRotationDegrees() {
        switch (getDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private static int computeRelativeRotation(CameraCharacteristics c, int surfaceRotationDegrees) {
        Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        int sensorDegrees = sensor == null ? 0 : sensor;
        int sign = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? 1 : -1;
        return (sensorDegrees - surfaceRotationDegrees * sign + 360) % 360;
    }

    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight,
                                          Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() * w == option.getWidth() * h) {
                if (option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        }
        Log.w(TAG, "No exact preview aspect-ratio match; using first available size");
        return choices[0];
    }

    private static final class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void showSavedIndicator() {
        runOnUiThread(() -> {
            if (rootView == null) return;
            if (savedIndicator != null && savedIndicator.getParent() == rootView) {
                rootView.removeView(savedIndicator);
            }

            TextView label = new TextView(this);
            label.setText("Saved");
            label.setTextColor(Color.WHITE);
            label.setTextSize(14f);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(18), dp(9), dp(18), dp(9));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xCC111111);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), 0x44FFFFFF);
            label.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(36);
            rootView.addView(label, lp);
            savedIndicator = label;

            label.setAlpha(0f);
            label.animate().alpha(1f).setDuration(80L).withEndAction(() ->
                    label.animate().alpha(0f).setStartDelay(650L).setDuration(140L)
                            .withEndAction(() -> {
                                if (label.getParent() == rootView) rootView.removeView(label);
                                if (savedIndicator == label) savedIndicator = null;
                            }).start()).start();
        });
    }

    private void showToast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
