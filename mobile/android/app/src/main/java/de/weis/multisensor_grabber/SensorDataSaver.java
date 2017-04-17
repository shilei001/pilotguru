package de.weis.multisensor_grabber;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.location.LocationListener;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Pair;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SensorDataSaver extends CameraCaptureSession.CaptureCallback implements
    SensorEventListener, LocationListener {
  public static String HEADINGS = "headings";
  public static String ACCELERATIONS = "accelerations";
  public static String LOCATIONS = "locations";
  public static String FRAMES = "frames";

  public static String SYSTEM_TIME_MSEC = "system_time_msec";
  public static String TIME_USEC = "time_usec";

  private JSONArray headings = null, accelerations = null, locations = null, frames = null;

  private boolean isRecording = false;
  private final ReadWriteLock recordingStatusLock = new ReentrantReadWriteLock();
  private final Lock gyroLock = recordingStatusLock.readLock();
  private final Lock accelerometerLock = recordingStatusLock.readLock();
  private final Lock locationLock = recordingStatusLock.readLock();
  private final Lock frameCaptureLock = recordingStatusLock.readLock();
  private final Lock recordingStatusChangeLock = recordingStatusLock.writeLock();

  private TextView textViewFps, textViewCamera;

  private File recordingDir;    // Parent directory where to write the current recording.
  // Frame timestamps for FPS computations.
  private long prevFrameSystemMicros = 0, currentFrameSystemMicros = 0;
  // Last filesystem free space query time.
  private long lastSpaceQueryTimeMicros = 0;
  // Most recent cached filesystem free space result in Gb.
  private double spaceAvailableGb = 0;

  public void start(@NonNull File recordingDir, TextView textViewFps, TextView textViewCamera) {
    try {
      recordingStatusChangeLock.lock();
      if (isRecording) {
        throw new AssertionError("Called start() but SensorDataSaver is already recording.");
      }
      isRecording = true;

      this.textViewFps = textViewFps;
      this.textViewCamera = textViewCamera;
      this.recordingDir = recordingDir;

      // Allocate new arrays for all the datastreams. We will accumulate the data in memory and
      // write it out to files in the end (on stop()) to avoid file IO in all the event handlers
      // during recording.
      headings = new JSONArray();
      accelerations = new JSONArray();
      locations = new JSONArray();
      frames = new JSONArray();
    } finally {
      recordingStatusChangeLock.unlock();
    }
  }

  public double getLastFps() {
    final long interFrameMicros =
        (prevFrameSystemMicros > 0) ? (currentFrameSystemMicros - prevFrameSystemMicros) : 0;
    return (interFrameMicros == 0) ? Double.NaN :
        ((double) TimeUnit.SECONDS.toMicros(1)) / (double) interFrameMicros;
  }

  public double getGbAvailable(File f, long currentTimeMicros) {
    // Only query the file system every couple of seconds to keep the load and latency down.
    if (currentTimeMicros - lastSpaceQueryTimeMicros > TimeUnit.SECONDS.toMicros(2)) {
      final StatFs stat = new StatFs(f.getPath());
      final long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
      spaceAvailableGb = ((double) bytesAvailable) * 1e-9;

      lastSpaceQueryTimeMicros = currentTimeMicros;
    }
    return spaceAvailableGb;
  }

  public void stop(Context context) {
    try {
      recordingStatusChangeLock.lock();
      if (!isRecording) {
        throw new AssertionError("Called stop() but SensorDataSaver is not recording.");
      }
      isRecording = false;

      final List<Pair<JSONArray, String>> outputDataItems = Arrays
          .asList(new Pair<>(headings, HEADINGS), new Pair<>(accelerations, ACCELERATIONS),
              new Pair<>(locations, LOCATIONS), new Pair<>(frames, FRAMES));
      for (Pair<JSONArray, String> outputData : outputDataItems) {
        final JSONObject outputJson = new JSONObject();
        final String outputName = outputData.second;
        outputJson.put(outputName, outputData.first);
        final File outputFile = new File(recordingDir, outputName + ".json");
        final Writer outputWriter = new BufferedWriter(new FileWriter(outputFile));
        outputWriter.write(outputJson.toString(2));
        outputWriter.close();
        // Make sure the files show up for the USB connection over MTP.
        MediaScannerConnection.scanFile(context, new String[]{outputFile.toString()}, null, null);
      }

      // Release all the data arrays.
      headings = null;
      accelerations = null;
      locations = null;
      frames = null;

    } catch (JSONException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      recordingStatusChangeLock.unlock();
    }
  }

  public boolean isRecording() {
    return isRecording;
  }

  // SensorEventListener: gyro and accelerations.

  public void onSensorChanged(SensorEvent event) {
    switch (event.sensor.getType()) {
      case Sensor.TYPE_GYROSCOPE:
        try {
          gyroLock.lock();
          if (isRecording) {
            final JSONObject heading = new JSONObject();
            heading.put("yaw", event.values[0]);
            heading.put("pitch", event.values[1]);
            heading.put("roll", event.values[2]);
            heading.put(TIME_USEC, TimeUnit.NANOSECONDS.toMicros(event.timestamp));
            heading.put(SYSTEM_TIME_MSEC, System.currentTimeMillis());
            headings.put(heading);
          }
        } catch (JSONException e) {
        } finally {
          gyroLock.unlock();
        }
        break;
      case Sensor.TYPE_ACCELEROMETER:
        try {
          accelerometerLock.lock();
          if (isRecording) {
            final JSONObject acceleration = new JSONObject();
            acceleration.put("x", event.values[0]);
            acceleration.put("y", event.values[1]);
            acceleration.put("z", event.values[2]);
            acceleration.put(TIME_USEC, TimeUnit.NANOSECONDS.toMicros(event.timestamp));
            acceleration.put(SYSTEM_TIME_MSEC, System.currentTimeMillis());
            accelerations.put(acceleration);
          }
        } catch (JSONException e) {
        } finally {
          accelerometerLock.unlock();
        }
        break;
      default:
        break;
    }
  }

  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  // LocationListener - GPS.
  public void onLocationChanged(Location location) {
    try {
      locationLock.lock();
      if (isRecording) {
        final JSONObject locationJson = new JSONObject();
        locationJson.put("lat", location.getLatitude());
        locationJson.put("lon", location.getLongitude());
        locationJson.put("accuracy_m", location.getAccuracy());
        locationJson.put("speed_m_s", location.getSpeed());
        locationJson.put("bearing_degrees", location.getBearing());
        locationJson.put("location_time_msec", location.getTime());
        locationJson.put(SYSTEM_TIME_MSEC, System.currentTimeMillis());
        locationJson.put("time_since_boot_usec",
            TimeUnit.NANOSECONDS.toMicros(location.getElapsedRealtimeNanos()));
        locations.put(locationJson);
      }
    } catch (JSONException e) {
    } finally {
      locationLock.unlock();
    }
  }

  public void onProviderDisabled(String provider) {
  }

  public void onProviderEnabled(String provider) {
  }

  public void onStatusChanged(String provider, int status, Bundle extras) {
  }

  // CaptureCallback - captured frames timestamps.

  public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                 TotalCaptureResult result) {
    try {
      frameCaptureLock.lock();
      if (isRecording) {
        // Make a timing log entry for the frame.
        final JSONObject frameJson = new JSONObject();
        frameJson.put("frame_id", result.getFrameNumber());
        final long frameSensorMicros =
            TimeUnit.NANOSECONDS.toMicros(result.get(CaptureResult.SENSOR_TIMESTAMP));
        frameJson.put(TIME_USEC, frameSensorMicros);
        frameJson.put(SYSTEM_TIME_MSEC, System.currentTimeMillis());
        frames.put(frameJson);

        prevFrameSystemMicros = currentFrameSystemMicros;
        currentFrameSystemMicros = frameSensorMicros;

        // Update FPS text view.
        if (textViewFps != null) {
          textViewFps.setText(String.format(Locale.US, "FPS: %.01f", getLastFps()));
        }

        // Update focus distance and stuff.
        if (textViewCamera != null) {
          Log.i("SensorDataSaver", "Updating camera.");
          final int whiteBalanceMode = result.get(CaptureResult.CONTROL_AWB_MODE);
          final double gbAvailable = getGbAvailable(recordingDir, frameSensorMicros);
          final String cameraText = String
              .format(Locale.US, "FOC: %s,  ISO: %s,  WB: %s,  Free space: %.02f Gb",
                  getFocalLengthText(result), getIsoSensitivity(result),
                  StringConverters.whiteBalanceModeToString(whiteBalanceMode), gbAvailable);
          textViewCamera.setText(cameraText);
        }
      }
    } catch (JSONException e) {
    } finally {
      frameCaptureLock.unlock();
    }
  }

  // Helpers for extracting camera info status bits for the status string.

  private String getFocalLengthText(CaptureResult result) {
    final boolean isFixedFocusDistance =
        result.get(CaptureResult.CONTROL_AF_MODE) == CaptureResult.CONTROL_AF_MODE_OFF;
    final Float focusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
    if (isFixedFocusDistance) {
      if (focusDistance != null) {
        return String.format(Locale.US, "Fixed: %0.1f", focusDistance);
      } else {
        return "NA";
      }
    } else {
      return "Auto";
    }
  }

  private String getIsoSensitivity(CaptureResult result) {
    final Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
    if (sensitivity != null) {
      return sensitivity.toString();
    } else {
      return "NA";
    }
  }
}
