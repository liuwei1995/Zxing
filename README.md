# Zxing
> 这是
```java
public CaptureActivityHandler(CaptureActivity activity,
                           Collection<BarcodeFormat> decodeFormats,
                           Map<DecodeHintType,?> baseHints,
                           String characterSet,
                           CameraManager cameraManager) {
        this.activity = activity;
        decodeThread = new DecodeThread(activity, decodeFormats, baseHints, characterSet,
                new ViewfinderResultPointCallback(activity.getViewfinderView()));
        decodeThread.start();
        state = State.SUCCESS;

        // Start ourselves capturing previews and decoding.
        this.cameraManager = cameraManager;
        cameraManager.startPreview();
        restartPreviewAndDecode();
    }

```
```javascript
var name = 'jack'

```