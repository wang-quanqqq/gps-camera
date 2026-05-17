package com.gpscamera.app;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ImageView previewThumbnail;
    private ImageButton btnCapture, btnSwitchCam, btnGallery;
    private TextView tvWatermarkPreview;
    private LinearLayout watermarkOverlay;

    private ImageCapture imageCapture;
    private int cameraFacing = CameraSelector.LENS_FACING_BACK;
    private FusedLocationProviderClient fusedLocationClient;
    private ExecutorService cameraExecutor;

    private double currentLat = 0, currentLng = 0;
    private String currentAddress = "定位中...";
    private boolean locationReady = false;

    // Watermark customization
    private boolean showLatLng = true;
    private boolean showAddress = true;
    private boolean showTime = true;
    private boolean showBattery = false;

    private static final int REQUEST_PERMISSIONS = 100;
    private static final String TAG = "GPSCamera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        previewThumbnail = findViewById(R.id.previewThumbnail);
        btnCapture = findViewById(R.id.btnCapture);
        btnSwitchCam = findViewById(R.id.btnSwitchCam);
        btnGallery = findViewById(R.id.btnGallery);
        tvWatermarkPreview = findViewById(R.id.tvWatermarkPreview);
        watermarkOverlay = findViewById(R.id.watermarkOverlay);

        cameraExecutor = Executors.newSingleThreadExecutor();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Watermark settings
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());

        btnCapture.setOnClickListener(v -> takePhoto());
        btnSwitchCam.setOnClickListener(v -> {
            cameraFacing = (cameraFacing == CameraSelector.LENS_FACING_BACK)
                    ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            startCamera();
        });
        btnGallery.setOnClickListener(v -> openGallery());

        previewThumbnail.setOnClickListener(v -> openGallery());

        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        String[] neededPermissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            neededPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        boolean allGranted = true;
        for (String p : neededPermissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            startCamera();
            getLocation();
        } else {
            ActivityCompat.requestPermissions(this, neededPermissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startCamera();
                getLocation();
            } else {
                Toast.makeText(this, "需要相机和位置权限才能使用", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "startCamera failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(cameraFacing)
                .build();

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.getDisplay().getRotation())
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
    }

    private void getLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        currentLat = location.getLatitude();
                        currentLng = location.getLongitude();
                        locationReady = true;
                        reverseGeocode(location);
                    } else {
                        requestNewLocation();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getLocation failed", e);
                    tvWatermarkPreview.setText("定位失败\n请检查GPS");
                });
    }

    private void requestNewLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;

        fusedLocationClient.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null
        ).addOnSuccessListener(location -> {
            if (location != null) {
                currentLat = location.getLatitude();
                currentLng = location.getLongitude();
                locationReady = true;
                reverseGeocode(location);
            }
        });
    }

    private void reverseGeocode(Location location) {
        try {
            Geocoder geocoder = new Geocoder(this, Locale.CHINA);
            List<Address> addresses = geocoder.getFromLocation(
                    location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i <= addr.getMaxAddressLineIndex(); i++) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(addr.getAddressLine(i));
                }
                currentAddress = sb.toString();
            } else {
                currentAddress = "未知地址";
            }
        } catch (IOException e) {
            currentAddress = "地址解析失败";
        }
        updateWatermarkPreview();
    }

    private void updateWatermarkPreview() {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date());
        StringBuilder sb = new StringBuilder();
        if (showTime) sb.append("时间: ").append(time).append("\n");
        if (showLatLng) {
            sb.append(String.format(Locale.CHINA, "纬度: %.6f\n经度: %.6f\n", currentLat, currentLng));
        }
        if (showAddress) sb.append("地址: ").append(currentAddress);
        tvWatermarkPreview.setText(sb.toString().trim());
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        updateWatermarkPreview();

        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageCapture.OutputFileResults results) {
                        // The actual save with watermark is done via takePicture with output options
                        // We'll use a different approach - save to temp then process
                        savePhotoWithWatermark();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(MainActivity.this, "拍照失败: " + exception.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void savePhotoWithWatermark() {
        File photoFile = createTempFile();

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        // Add watermark
                        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                        if (bitmap != null) {
                            Bitmap watermarked = addWatermark(bitmap);
                            saveFinalImage(watermarked);
                            bitmap.recycle();
                        }
                        // Delete temp
                        photoFile.delete();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "保存失败",
                                        Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private File createTempFile() {
        File dir = getCacheDir();
        return new File(dir, "temp_" + System.currentTimeMillis() + ".jpg");
    }

    private Bitmap addWatermark(Bitmap source) {
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        String dateStr = sdf.format(new Date());

        float density = getResources().getDisplayMetrics().density;
        float textSize = 12 * density;
        float padding = 12 * density;

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#80000000"));

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        // Build watermark text
        StringBuilder watermarkText = new StringBuilder();
        if (showTime) watermarkText.append("时间: ").append(dateStr).append("\n");
        if (showLatLng) {
            watermarkText.append(String.format(Locale.CHINA, "纬度: %.6f\n经度: %.6f\n",
                    currentLat, currentLng));
        }
        if (showAddress) watermarkText.append("地址: ").append(currentAddress);

        String[] lines = watermarkText.toString().trim().split("\n");

        // Calculate text dimensions
        float maxWidth = 0;
        float totalHeight = 0;
        float lineSpacing = 4 * density;
        for (String line : lines) {
            float w = textPaint.measureText(line);
            if (w > maxWidth) maxWidth = w;
            totalHeight += textSize + lineSpacing;
        }
        totalHeight -= lineSpacing; // remove last spacing

        float bgLeft = padding;
        float bgTop = result.getHeight() - padding - totalHeight - padding * 2;
        float bgRight = bgLeft + maxWidth + padding * 2;
        float bgBottom = result.getHeight() - padding;

        // Draw semi-transparent background
        canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, 8 * density, 8 * density, bgPaint);

        // Draw text
        float textX = bgLeft + padding;
        float textY = bgTop + padding + textSize;
        for (String line : lines) {
            canvas.drawText(line, textX, textY, textPaint);
            textY += textSize + lineSpacing;
        }

        return result;
    }

    private void saveFinalImage(Bitmap watermarked) {
        String fileName = "GPSCamera_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".jpg";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/GPSCamera");

                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (FileOutputStream out = (FileOutputStream)
                            getContentResolver().openOutputStream(uri)) {
                        watermarked.compress(Bitmap.CompressFormat.JPEG, 95, out);
                    }
                }
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "GPSCamera");
                dir.mkdirs();
                File file = new File(dir, fileName);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    watermarked.compress(Bitmap.CompressFormat.JPEG, 95, out);
                }

                // Notify gallery
                Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                intent.setData(Uri.fromFile(file));
                sendBroadcast(intent);
            }

            watermarked.recycle();

            runOnUiThread(() -> {
                Toast.makeText(this, "已保存: " + fileName, Toast.LENGTH_SHORT).show();
                loadLatestThumbnail();
            });

        } catch (IOException e) {
            Log.e(TAG, "saveFinalImage failed", e);
            runOnUiThread(() ->
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
        }
    }

    private void loadLatestThumbnail() {
        cameraExecutor.execute(() -> {
            try {
                Uri collection;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    collection = MediaStore.Images.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY);
                } else {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }

                String selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? OR "
                        + MediaStore.Images.Media.DATA + " LIKE ?";
                String[] selectionArgs = new String[]{"%GPSCamera%", "%GPSCamera%"};

                String[] projection = {MediaStore.Images.Media._ID};
                var cursor = getContentResolver().query(
                        collection, projection, selection, selectionArgs,
                        MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT 1");

                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    Uri thumbUri = Uri.withAppendedPath(collection, String.valueOf(id));
                    runOnUiThread(() -> {
                        previewThumbnail.setImageURI(thumbUri);
                        previewThumbnail.setVisibility(View.VISIBLE);
                    });
                    cursor.close();
                }
            } catch (Exception ignored) {}
        });
    }

    private void openGallery() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            intent = new Intent(Intent.ACTION_PICK,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }
        startActivity(Intent.createChooser(intent, "查看照片"));
    }

    private void showSettingsDialog() {
        String[] items = {
                "显示经纬度" + (showLatLng ? " ✓" : ""),
                "显示地址" + (showAddress ? " ✓" : ""),
                "显示时间" + (showTime ? " ✓" : "")
        };
        boolean[] checked = {showLatLng, showAddress, showTime};

        new AlertDialog.Builder(this)
                .setTitle("水印设置")
                .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> {
                    switch (which) {
                        case 0: showLatLng = isChecked; break;
                        case 1: showAddress = isChecked; break;
                        case 2: showTime = isChecked; break;
                    }
                })
                .setPositiveButton("确定", (dialog, which) -> updateWatermarkPreview())
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
