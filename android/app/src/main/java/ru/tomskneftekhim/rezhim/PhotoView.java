package ru.tomskneftekhim.rezhim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pinch-zoom photo viewer. A downsampled copy fills the screen; when zoomed in, the visible
 * part is decoded again from the original at the resolution the screen needs, so small digits
 * on 64-MP HMI photos stay readable without holding the full image in memory.
 */
final class PhotoView extends View {
  interface Opener { InputStream open() throws IOException; }

  private static final int PREVIEW_MAX = 2048;
  private static final float MAX_SCALE = 4f;

  private final Opener opener;
  private final int orientation;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Matrix rawToOriented = new Matrix();
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
  private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ScaleGestureDetector scaler;
  private final GestureDetector gestures;
  private BitmapRegionDecoder decoder;
  private Bitmap preview, detail;
  private final Rect detailRect = new Rect();
  private int rawWidth, rawHeight, previewSample = 1, generation;
  private float width, height, scale, minScale, tx, ty;
  private float[] pendingRegion;
  private String error;
  private boolean fitted, detached;

  PhotoView(Context context, Opener opener, int orientation) {
    super(context);
    this.opener = opener;
    this.orientation = orientation;
    setBackgroundColor(Color.BLACK);
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
    scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
      @Override public boolean onScale(ScaleGestureDetector detector) {
        zoomTo(scale * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
        return true;
      }
    });
    gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
      @Override public boolean onScroll(MotionEvent first, MotionEvent event, float dx, float dy) {
        tx -= dx;
        ty -= dy;
        constrain();
        changed();
        return true;
      }

      @Override public boolean onDoubleTap(MotionEvent event) {
        if (scale > minScale * 1.5f) fit(); else zoomTo(minScale * 4, event.getX(), event.getY());
        return true;
      }
    });
    worker.execute(this::load);
  }

  @SuppressWarnings("deprecation")
  private void load() {
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      try (InputStream in = opener.open()) { BitmapFactory.decodeStream(in, null, bounds); }
      if (bounds.outWidth <= 0) throw new IOException("Файл не распознан как изображение");
      InputStream in = opener.open();
      BitmapRegionDecoder created = Build.VERSION.SDK_INT >= 31 ? BitmapRegionDecoder.newInstance(in)
        : BitmapRegionDecoder.newInstance(in, false);
      int sample = 1;
      while (Math.max(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX) sample *= 2;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = sample;
      Bitmap bitmap = created.decodeRegion(new Rect(0, 0, bounds.outWidth, bounds.outHeight), options);
      int finalSample = sample;
      main.post(() -> {
        if (detached) {
          bitmap.recycle();
          created.recycle();
          return;
        }
        decoder = created;
        rawWidth = bounds.outWidth;
        rawHeight = bounds.outHeight;
        previewSample = finalSample;
        preview = bitmap;
        orient();
        fitted = false;
        if (getWidth() > 0) layoutImage();
        invalidate();
      });
    } catch (Exception | OutOfMemoryError failure) {
      main.post(() -> {
        error = "Не удалось открыть фото: " + failure.getMessage();
        invalidate();
      });
    }
  }

  private void orient() {
    rawToOriented.reset();
    switch (orientation) {
      case 3:
        rawToOriented.setRotate(180);
        rawToOriented.postTranslate(rawWidth, rawHeight);
        width = rawWidth; height = rawHeight;
        break;
      case 6:
        rawToOriented.setRotate(90);
        rawToOriented.postTranslate(rawHeight, 0);
        width = rawHeight; height = rawWidth;
        break;
      case 8:
        rawToOriented.setRotate(270);
        rawToOriented.postTranslate(0, rawWidth);
        width = rawHeight; height = rawWidth;
        break;
      default:
        width = rawWidth; height = rawHeight;
    }
  }

  @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    if (preview != null) {
      fitted = false;
      layoutImage();
    }
  }

  private void layoutImage() {
    minScale = Math.min(getWidth() / width, getHeight() / height);
    if (!fitted) {
      fitted = true;
      if (pendingRegion != null) showRegion(pendingRegion); else fit();
    } else {
      constrain();
      changed();
    }
  }

  private void fit() {
    scale = minScale;
    tx = (getWidth() - width * scale) / 2;
    ty = (getHeight() - height * scale) / 2;
    changed();
  }

  private void zoomTo(float target, float focusX, float focusY) {
    if (preview == null) return;
    float next = Math.max(minScale, Math.min(MAX_SCALE, target));
    tx = focusX - (focusX - tx) * (next / scale);
    ty = focusY - (focusY - ty) * (next / scale);
    scale = next;
    constrain();
    changed();
  }

  private void constrain() {
    float shownWidth = width * scale, shownHeight = height * scale;
    tx = shownWidth <= getWidth() ? (getWidth() - shownWidth) / 2 : Math.max(getWidth() - shownWidth, Math.min(0, tx));
    ty = shownHeight <= getHeight() ? (getHeight() - shownHeight) / 2 : Math.max(getHeight() - shownHeight, Math.min(0, ty));
  }

  /** Zooms to a region given as fractions of the upright photo: left, top, right, bottom. */
  void showRegion(float[] region) {
    if (preview == null || getWidth() == 0) {
      pendingRegion = region;
      return;
    }
    pendingRegion = null;
    float regionWidth = Math.max(0.02f, region[2] - region[0]) * width;
    float regionHeight = Math.max(0.02f, region[3] - region[1]) * height;
    scale = Math.max(minScale, Math.min(MAX_SCALE, Math.min(getWidth() / regionWidth, getHeight() / regionHeight)));
    float centerX = (region[0] + region[2]) / 2 * width, centerY = (region[1] + region[3]) / 2 * height;
    tx = getWidth() / 2f - centerX * scale;
    ty = getHeight() / 2f - centerY * scale;
    constrain();
    changed();
  }

  /** The part of the upright photo on screen, as fractions: left, top, right, bottom. */
  float[] visibleRegion() {
    if (preview == null) return null;
    float left = Math.max(0, -tx / scale), top = Math.max(0, -ty / scale);
    float right = Math.min(width, (getWidth() - tx) / scale), bottom = Math.min(height, (getHeight() - ty) / scale);
    return new float[]{left / width, top / height, right / width, bottom / height};
  }

  private final Runnable detailTask = this::requestDetail;

  private void changed() {
    invalidate();
    main.removeCallbacks(detailTask);
    main.postDelayed(detailTask, 150);
  }

  private void requestDetail() {
    if (decoder == null || preview == null) return;
    if (scale * previewSample <= 1.05f) {
      if (detail != null) { detail.recycle(); detail = null; invalidate(); }
      return;
    }
    RectF visible = new RectF(Math.max(0, -tx / scale), Math.max(0, -ty / scale),
      Math.min(width, (getWidth() - tx) / scale), Math.min(height, (getHeight() - ty) / scale));
    Matrix orientedToRaw = new Matrix();
    rawToOriented.invert(orientedToRaw);
    orientedToRaw.mapRect(visible);
    Rect region = new Rect();
    visible.roundOut(region);
    if (!region.intersect(0, 0, rawWidth, rawHeight)) return;
    int sample = 1;
    while (sample * 2 <= 1 / scale) sample *= 2;
    int ticket = ++generation;
    int finalSample = sample;
    worker.execute(() -> {
      if (ticket != generation) return;
      try {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = finalSample;
        Bitmap bitmap = decoder.decodeRegion(region, options);
        main.post(() -> {
          if (ticket != generation || bitmap == null) { if (bitmap != null) bitmap.recycle(); return; }
          if (detail != null) detail.recycle();
          detail = bitmap;
          detailRect.set(region);
          invalidate();
        });
      } catch (Exception | OutOfMemoryError ignored) {
        // The preview stays on screen; a later gesture retries the detail.
      }
    });
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (error != null) {
      canvas.drawText(error, 24, getHeight() / 2f, textPaint);
      return;
    }
    if (preview == null) {
      canvas.drawText("Загрузка фото…", 24, getHeight() / 2f, textPaint);
      return;
    }
    canvas.save();
    canvas.translate(tx, ty);
    canvas.scale(scale, scale);
    canvas.concat(rawToOriented);
    canvas.drawBitmap(preview, null, new RectF(0, 0, rawWidth, rawHeight), paint);
    if (detail != null) canvas.drawBitmap(detail, null, new RectF(detailRect), paint);
    canvas.restore();
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    boolean handled = scaler.onTouchEvent(event);
    handled |= gestures.onTouchEvent(event);
    return handled || super.onTouchEvent(event);
  }

  @Override protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    detached = true;
    generation++;
    main.removeCallbacks(detailTask);
    worker.execute(() -> {
      if (decoder != null) decoder.recycle();
    });
    worker.shutdown();
  }

  /** A small upright copy for lists. */
  static Bitmap thumbnail(Opener opener, int orientation, int maxSide) throws IOException {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try (InputStream in = opener.open()) { BitmapFactory.decodeStream(in, null, bounds); }
    int sample = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    Bitmap bitmap;
    try (InputStream in = opener.open()) { bitmap = BitmapFactory.decodeStream(in, null, options); }
    if (bitmap == null) throw new IOException("Файл не распознан как изображение");
    int degrees = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
    if (degrees == 0) return bitmap;
    Matrix rotation = new Matrix();
    rotation.setRotate(degrees);
    Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotation, true);
    if (rotated != bitmap) bitmap.recycle();
    return rotated;
  }
}
