package de.weis.multisensor_grabber;

import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.CamcorderProfile;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO;
import static de.weis.multisensor_grabber.SettingsConstants.PREF_FIXED_FOCUS_DIST;
import static de.weis.multisensor_grabber.SettingsConstants.PREF_FIXED_ISO;
import static de.weis.multisensor_grabber.SettingsConstants.PREF_FOCUS_DIST;
import static de.weis.multisensor_grabber.SettingsConstants.PREF_ISO;
import static de.weis.multisensor_grabber.SettingsConstants.PREF_WHITE_BALANCE;

public class CaptureSettings implements SerializableSequenceElement {
  private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
  static {
    ORIENTATIONS.append(Surface.ROTATION_0, 90);
    ORIENTATIONS.append(Surface.ROTATION_90, 0);
    ORIENTATIONS.append(Surface.ROTATION_180, 270);
    ORIENTATIONS.append(Surface.ROTATION_270, 180);
  }

  private final boolean isFixedFocusDistance;
  private final boolean isFixedIso;
  private final float focusDistance;
  private final int whiteBalanceMode;
  private final int isoSensitivity;
  private final int qualityProfile;

  CaptureSettings(SharedPreferences prefs) {
    isFixedFocusDistance = prefs.getBoolean(PREF_FIXED_FOCUS_DIST, false);
    isFixedIso = prefs.getBoolean(PREF_FIXED_ISO, false);

    focusDistance = Float.parseFloat(prefs.getString(PREF_FOCUS_DIST, "-1"));
    whiteBalanceMode = Integer
        .parseInt(prefs.getString(PREF_WHITE_BALANCE, Integer.toString(CONTROL_AWB_MODE_AUTO)));
    isoSensitivity = Integer.parseInt(prefs.getString(PREF_ISO, "-1"));
    qualityProfile = Integer.parseInt(prefs.getString(SettingsConstants.PREF_RESOLUTIONS, "-1"));
    if (qualityProfile < 0) {
      throw new AssertionError("Invalid camcorder quality resolution enum code: " + qualityProfile);
    }
  }

  public CamcorderProfile getSupportedQualityProfile() {
    final boolean hasPreferredProfile = CamcorderProfile.hasProfile(qualityProfile);
    if (!hasPreferredProfile) {
      Log.w("SensorGrabberMain", "Preferred quality profile " + qualityProfile +
          " not supported, falling back to low quality.");
    }
    return CamcorderProfile
        .get(hasPreferredProfile ? qualityProfile : CamcorderProfile.QUALITY_LOW);
  }

  public void serialize(XmlSerializer serializer) throws IOException {
    serializer.attribute(null, "wb_value", Integer.toString(whiteBalanceMode));

    if (isFixedIso) {
      serializer.attribute(null, "iso_value", Integer.toString(isoSensitivity));
    } else {
      serializer.attribute(null, "iso_value", "-1");
    }

    if (isFixedFocusDistance) {
      serializer.attribute(null, "foc_dist", Float.toString(focusDistance));
    } else {
      serializer.attribute(null, "foc_dist", "-1");
    }
  }

  public CaptureRequest.Builder makeCaptureRequestBuilder(CameraDevice cameraDevice,
                                                          Iterable<Surface> outputSurfaces, int rotation)
      throws CameraAccessException {
    final CaptureRequest.Builder captureBuilder =
        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
    captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    for (Surface outputSurface : outputSurfaces) {
      captureBuilder.addTarget(outputSurface);
    }

    if (isFixedIso && isoSensitivity != -1) {
      captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, isoSensitivity);
    }


    /************************************** FOCUS ********************************/
    if (isFixedFocusDistance) {
      captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
      captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CONTROL_AF_MODE_OFF);
    }

    /************************************** WHITEBALANCE ********************************/

    captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode);

    /************************************** REST ********************************/
    // http://stackoverflow.com/questions/29265126/android-camera2-capture-burst-is-too-slow
    captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
    captureBuilder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF);
    captureBuilder
        .set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
    captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);

    captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));

    return captureBuilder;
  }
}
