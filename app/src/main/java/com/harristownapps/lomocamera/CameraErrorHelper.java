package com.harristownapps.lomocamera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;

/**
 * Utility helper to map Camera2 device errors and access exception reason codes
 * to clear, human-readable, non-alarmist descriptions.
 */
public final class CameraErrorHelper {

    private CameraErrorHelper() {
        // Prevent instantiation
    }

    /**
     * Resolves error codes from {@link CameraDevice.StateCallback#onError(CameraDevice, int)}
     * to a descriptive user message.
     *
     * @param errorCode The error code passed to StateCallback.onError.
     * @return A clear, user-facing error message.
     */
    public static String getDeviceErrorMessage(int errorCode) {
        switch (errorCode) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                return "Camera is already in use by another app";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "Too many camera applications are open";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "Camera is disabled by device policy";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                return "Camera device encountered an error";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                return "Camera service encountered an error";
            default:
                return "Camera error (code " + errorCode + ")";
        }
    }

    /**
     * Resolves reason codes from {@link CameraAccessException#getReason()}
     * to a descriptive user message.
     *
     * @param reasonCode The reason code from CameraAccessException.
     * @return A clear, user-facing access error message.
     */
    public static String getAccessErrorMessage(int reasonCode) {
        switch (reasonCode) {
            case CameraAccessException.CAMERA_IN_USE:
                return "Camera is currently in use";
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                return "Maximum camera limit reached";
            case CameraAccessException.CAMERA_DISABLED:
                return "Camera access is disabled";
            case CameraAccessException.CAMERA_DISCONNECTED:
                return "Camera was disconnected";
            case CameraAccessException.CAMERA_ERROR:
                return "Camera encountered an error";
            default:
                return "Camera unavailable (code " + reasonCode + ")";
        }
    }
}
