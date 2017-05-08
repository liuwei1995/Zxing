package com.liuwei1995.zxing.zxing;

/**
 * Created by linxins on 17-5-8.
 */

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.liuwei1995.zxing.R;
import com.liuwei1995.zxing.zxing.camera.CameraManager;
import com.liuwei1995.zxing.zxing.handler.CaptureActivityHandler;
import com.liuwei1995.zxing.zxing.manager.DecodeFormatManager;
import com.liuwei1995.zxing.zxing.result.ResultHandler;
import com.liuwei1995.zxing.zxing.result.ResultHandlerFactory;
import com.liuwei1995.zxing.zxing.view.ViewfinderView;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;

/**
 * This activity opens the camera and does the actual scanning on a background thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = CaptureActivity.class.getSimpleName();

    private static final long DEFAULT_INTENT_RESULT_DURATION_MS = 1500L;
    private static final long BULK_MODE_SCAN_DELAY_MS = 1000L;

    private static final String[] ZXING_URLS = { "http://zxing.appspot.com/scan", "zxing://scan/" };

    private static final int HISTORY_REQUEST_CODE = 0x0000bacc;

    private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES =
            EnumSet.of(ResultMetadataType.ISSUE_NUMBER,
                    ResultMetadataType.SUGGESTED_PRICE,
                    ResultMetadataType.ERROR_CORRECTION_LEVEL,
                    ResultMetadataType.POSSIBLE_COUNTRY);

    private CameraManager cameraManager;
    private CaptureActivityHandler handler;
    private Result savedResultToShow;
    private ViewfinderView viewfinderView;
    private Result lastResult;
    private boolean hasSurface;
    private boolean copyToClipboard;
    private String sourceUrl;
    private Collection<BarcodeFormat> decodeFormats;
    private String characterSet;
    private InactivityTimer inactivityTimer;
    private AmbientLightManager ambientLightManager;

    public ViewfinderView getViewfinderView() {
        return viewfinderView;
    }

    public Handler getHandler() {
        return handler;
    }

    public CameraManager getCameraManager() {
        return cameraManager;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.capture);

        hasSurface = false;
        inactivityTimer = new InactivityTimer(this);
        ambientLightManager = new AmbientLightManager(this);

    }

    @Override
    protected void onResume() {
        super.onResume();

        // historyManager must be initialized here to update the history preference

        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        cameraManager = new CameraManager(getApplication());

        viewfinderView = (ViewfinderView) findViewById(R.id.viewfinder_view);
        viewfinderView.setCameraManager(cameraManager);

        handler = null;
        lastResult = null;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (prefs.getBoolean(PreferencesContent.KEY_DISABLE_AUTO_ORIENTATION, true)) {
            setRequestedOrientation(getCurrentOrientation());
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        ambientLightManager.start(cameraManager);

        inactivityTimer.onResume();

        Intent intent = getIntent();

        copyToClipboard = prefs.getBoolean(PreferencesContent.KEY_COPY_TO_CLIPBOARD, true)
                && (intent == null || intent.getBooleanExtra(Intents.Scan.SAVE_HISTORY, true));

        sourceUrl = null;
        decodeFormats = null;
        characterSet = null;

        if (intent != null) {

            String action = intent.getAction();
            String dataString = intent.getDataString();

            if (Intents.Scan.ACTION.equals(action)) {

                // Scan the formats the intent requested, and return the result to the calling activity.
                decodeFormats = DecodeFormatManager.parseDecodeFormats(intent);

                if (intent.hasExtra(Intents.Scan.WIDTH) && intent.hasExtra(Intents.Scan.HEIGHT)) {
                    int width = intent.getIntExtra(Intents.Scan.WIDTH, 0);
                    int height = intent.getIntExtra(Intents.Scan.HEIGHT, 0);
                    if (width > 0 && height > 0) {
                        cameraManager.setManualFramingRect(width, height);
                    }
                }

                if (intent.hasExtra(Intents.Scan.CAMERA_ID)) {
                    int cameraId = intent.getIntExtra(Intents.Scan.CAMERA_ID, -1);
                    if (cameraId >= 0) {
                        cameraManager.setManualCameraId(cameraId);
                    }
                }


            } else if (dataString != null &&
                    dataString.contains("http://www.google") &&
                    dataString.contains("/m/products/scan")) {

                // Scan only products and send the result to mobile Product Search.
                sourceUrl = dataString;
                decodeFormats = DecodeFormatManager.PRODUCT_FORMATS;

            } else if (isZXingURL(dataString)) {

                // Scan formats requested in query string (all formats if none specified).
                // If a return URL is specified, send the results there. Otherwise, handle it ourselves.
                sourceUrl = dataString;
                Uri inputUri = Uri.parse(dataString);
                decodeFormats = DecodeFormatManager.parseDecodeFormats(inputUri);
                // Allow a sub-set of the hints to be specified by the caller.

            }

            characterSet = intent.getStringExtra(Intents.Scan.CHARACTER_SET);

        }

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder);
        } else {
            // Install the callback and wait for surfaceCreated() to init the camera.
            surfaceHolder.addCallback(this);
        }
    }

    private int getCurrentOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_90:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
        } else {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_270:
                    return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            }
        }
    }

    private static boolean isZXingURL(String dataString) {
        if (dataString == null) {
            return false;
        }
        for (String url : ZXING_URLS) {
            if (dataString.startsWith(url)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onPause() {
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }
        inactivityTimer.onPause();
        ambientLightManager.stop();
        cameraManager.closeDriver();
        //historyManager = null; // Keep for onActivityResult
        if (!hasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        inactivityTimer.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                break;
            case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_CAMERA:
                // Handle these events so they don't launch the Camera app
                return true;
            // Use volume up/down to turn on light
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                cameraManager.setTorch(false);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                cameraManager.setTorch(true);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    }

    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
        // Bitmap isn't used yet -- will be used soon
        if (handler == null) {
            savedResultToShow = result;
        } else {
            if (result != null) {
                savedResultToShow = result;
            }
            if (savedResultToShow != null) {
                Message message = Message.obtain(handler, R.id.decode_succeeded, savedResultToShow);
                handler.sendMessage(message);
            }
            savedResultToShow = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!hasSurface) {
            hasSurface = true;
            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    /**
     * A valid barcode has been found, so give an indication of success and show the results.
     *
     * @param rawResult The contents of the barcode.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param barcode   A greyscale bitmap of the camera data which was decoded.
     */
    public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
        inactivityTimer.onActivity();
        lastResult = rawResult;
        ResultHandler resultHandler = ResultHandlerFactory.makeResultHandler(this, rawResult);
        Toast.makeText(this, rawResult.getText(), Toast.LENGTH_SHORT).show();

//        boolean fromLiveScan = barcode != null;
//        if (fromLiveScan) {
//            drawResultPoints(barcode, scaleFactor, rawResult);
//        }


    }

    /**
     * Superimpose a line for 1D or dots for 2D to highlight the key features of the barcode.
     *
     * @param barcode   A bitmap of the captured image.
     * @param scaleFactor amount by which thumbnail was scaled
     * @param rawResult The decoded results which contains the points to draw.
     */
//    private void drawResultPoints(Bitmap barcode, float scaleFactor, Result rawResult) {
//        ResultPoint[] points = rawResult.getResultPoints();
//        if (points != null && points.length > 0) {
//            Canvas canvas = new Canvas(barcode);
//            Paint paint = new Paint();
//            paint.setColor(getResources().getColor(R.color.result_points));
//            if (points.length == 2) {
//                paint.setStrokeWidth(4.0f);
//                drawLine(canvas, paint, points[0], points[1], scaleFactor);
//            } else if (points.length == 4 &&
//                    (rawResult.getBarcodeFormat() == BarcodeFormat.UPC_A ||
//                            rawResult.getBarcodeFormat() == BarcodeFormat.EAN_13)) {
//                // Hacky special case -- draw two lines, for the barcode and metadata
//                drawLine(canvas, paint, points[0], points[1], scaleFactor);
//                drawLine(canvas, paint, points[2], points[3], scaleFactor);
//            } else {
//                paint.setStrokeWidth(10.0f);
//                for (ResultPoint point : points) {
//                    if (point != null) {
//                        canvas.drawPoint(scaleFactor * point.getX(), scaleFactor * point.getY(), paint);
//                    }
//                }
//            }
//        }
//    }

//    private static void drawLine(Canvas canvas, Paint paint, ResultPoint a, ResultPoint b, float scaleFactor) {
//        if (a != null && b != null) {
//            canvas.drawLine(scaleFactor * a.getX(),
//                    scaleFactor * a.getY(),
//                    scaleFactor * b.getX(),
//                    scaleFactor * b.getY(),
//                    paint);
//        }
//    }

    // Put up our own UI for how to handle the decoded contents.
    private void handleDecodeInternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (resultHandler.getDefaultButtonID() != null && prefs.getBoolean(PreferencesContent.KEY_AUTO_OPEN_WEB, false)) {
            resultHandler.handleButtonPress(resultHandler.getDefaultButtonID());
            return;
        }

        viewfinderView.setVisibility(View.GONE);
    }

    // Briefly show the contents of the barcode, then handle the result outside Barcode Scanner.
    private void handleDecodeExternally(Result rawResult, ResultHandler resultHandler, Bitmap barcode) {

        if (barcode != null) {
            viewfinderView.drawResultBitmap(barcode);
        }

        long resultDurationMS;
        if (getIntent() == null) {
            resultDurationMS = DEFAULT_INTENT_RESULT_DURATION_MS;
        } else {
            resultDurationMS = getIntent().getLongExtra(Intents.Scan.RESULT_DISPLAY_DURATION_MS,
                    DEFAULT_INTENT_RESULT_DURATION_MS);
        }

        if (resultDurationMS > 0) {
            String rawResultString = String.valueOf(rawResult);
            if (rawResultString.length() > 32) {
                rawResultString = rawResultString.substring(0, 32) + " ...";
            }
        }
    }


    private void sendReplyMessage(int id, Object arg, long delayMS) {
        if (handler != null) {
            Message message = Message.obtain(handler, id, arg);
            if (delayMS > 0L) {
                handler.sendMessageDelayed(message, delayMS);
            } else {
                handler.sendMessage(message);
            }
        }
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (cameraManager.isOpen()) {
            Log.w(TAG, "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            cameraManager.openDriver(surfaceHolder);
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (handler == null) {
                handler = new CaptureActivityHandler(this, decodeFormats, null, characterSet, cameraManager);
            }
            decodeOrStoreSavedBitmap(null, null);
        } catch (IOException ioe) {
            Log.w(TAG, ioe);
//            displayFrameworkBugMessageAndExit();
        } catch (RuntimeException e) {
            // Barcode Scanner has seen crashes in the wild of this variety:
            // java.?lang.?RuntimeException: Fail to connect to camera service
            Log.w(TAG, "Unexpected error initializing camera", e);
//            displayFrameworkBugMessageAndExit();
        }
    }




    public void drawViewfinder() {
        viewfinderView.drawViewfinder();
    }
}