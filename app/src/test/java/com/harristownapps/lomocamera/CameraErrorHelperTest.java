package com.harristownapps.lomocamera;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CameraErrorHelperTest {

    @Test
    public void testGetDeviceErrorMessage_knownErrors() {
        assertEquals("Camera is already in use by another app",
                CameraErrorHelper.getDeviceErrorMessage(CameraDevice.StateCallback.ERROR_CAMERA_IN_USE));
        assertEquals("Too many camera applications are open",
                CameraErrorHelper.getDeviceErrorMessage(CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE));
        assertEquals("Camera is disabled by device policy",
                CameraErrorHelper.getDeviceErrorMessage(CameraDevice.StateCallback.ERROR_CAMERA_DISABLED));
        assertEquals("Camera device encountered an error",
                CameraErrorHelper.getDeviceErrorMessage(CameraDevice.StateCallback.ERROR_CAMERA_DEVICE));
        assertEquals("Camera service encountered an error",
                CameraErrorHelper.getDeviceErrorMessage(CameraDevice.StateCallback.ERROR_CAMERA_SERVICE));
    }

    @Test
    public void testGetDeviceErrorMessage_unknownErrors() {
        assertEquals("Camera error (code -1)",
                CameraErrorHelper.getDeviceErrorMessage(-1));
        assertEquals("Camera error (code 0)",
                CameraErrorHelper.getDeviceErrorMessage(0));
        assertEquals("Camera error (code 99)",
                CameraErrorHelper.getDeviceErrorMessage(99));
    }

    @Test
    public void testGetAccessErrorMessage_knownReasons() {
        assertEquals("Camera was disconnected",
                CameraErrorHelper.getAccessErrorMessage(CameraAccessException.CAMERA_DISCONNECTED));
        assertEquals("Camera access is disabled",
                CameraErrorHelper.getAccessErrorMessage(CameraAccessException.CAMERA_DISABLED));
        assertEquals("Camera encountered an error",
                CameraErrorHelper.getAccessErrorMessage(CameraAccessException.CAMERA_ERROR));
        assertEquals("Camera is currently in use",
                CameraErrorHelper.getAccessErrorMessage(CameraAccessException.CAMERA_IN_USE));
        assertEquals("Maximum camera limit reached",
                CameraErrorHelper.getAccessErrorMessage(CameraAccessException.MAX_CAMERAS_IN_USE));
    }

    @Test
    public void testGetAccessErrorMessage_unknownReasons() {
        assertEquals("Camera unavailable (code -1)",
                CameraErrorHelper.getAccessErrorMessage(-1));
        assertEquals("Camera unavailable (code 0)",
                CameraErrorHelper.getAccessErrorMessage(0));
        assertEquals("Camera unavailable (code 99)",
                CameraErrorHelper.getAccessErrorMessage(99));
    }
}
