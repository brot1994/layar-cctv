package com.agi.remotetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Bingkai 16:9 (seperti layar TV). Gambar digeser untuk mengatur posisi,
 * dicubit untuk zoom (bila diizinkan). Gambar selalu menutupi bingkai penuh.
 */
final class WallCropView extends View {
    private final Bitmap bmp;
    private final boolean zoomable;
    private final Matrix m = new Matrix();
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector sgd;
    private float s, cover, tx, ty, lastX, lastY;
    private boolean inited;

    WallCropView(Context c, Bitmap bmp, boolean zoomable) {
        super(c);
        this.bmp = bmp;
        this.zoomable = zoomable;
        pLine.setColor(0x66FFFFFF);
        pLine.setStrokeWidth(1.5f);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (!WallCropView.this.zoomable) return true;
                float ns = Math.max(cover, Math.min(cover * 5f, s * d.getScaleFactor()));
                float f = ns / s;
                tx = d.getFocusX() - (d.getFocusX() - tx) * f;
                ty = d.getFocusY() - (d.getFocusY() - ty) * f;
                s = ns;
                clamp();
                invalidate();
                return true;
            }
        });
        setBackgroundColor(Color.BLACK);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, w * 9 / 16);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        cover = Math.max((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        if (!inited) {
            s = cover;
            tx = (w - bmp.getWidth() * s) / 2f;
            ty = (h - bmp.getHeight() * s) / 2f;
            inited = true;
        }
        clamp();
    }

    private void clamp() {
        float w = getWidth(), h = getHeight();
        if (s < cover) s = cover;
        float bw = bmp.getWidth() * s, bh = bmp.getHeight() * s;
        tx = Math.min(0, Math.max(w - bw, tx));
        ty = Math.min(0, Math.max(h - bh, ty));
    }

    @Override
    protected void onDraw(Canvas c) {
        m.reset();
        m.postScale(s, s);
        m.postTranslate(tx, ty);
        c.drawBitmap(bmp, m, pBmp);
        float w = getWidth(), h = getHeight();
        for (int i = 1; i < 3; i++) {   // garis bantu sepertiga
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, pLine);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, pLine);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastX = e.getX(); lastY = e.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                lastX = avgX(e); lastY = avgY(e);
                break;
            case MotionEvent.ACTION_MOVE:
                float x = avgX(e), y = avgY(e);
                tx += x - lastX; ty += y - lastY;
                lastX = x; lastY = y;
                clamp();
                invalidate();
                break;
        }
        return true;
    }

    private static float avgX(MotionEvent e) {
        float t = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
            t += e.getX(i); n++;
        }
        return n == 0 ? e.getX() : t / n;
    }

    private static float avgY(MotionEvent e) {
        float t = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
            t += e.getY(i); n++;
        }
        return n == 0 ? e.getY() : t / n;
    }

    /** Bagian gambar (koordinat bitmap) yang tampak di bingkai. */
    RectF crop() {
        return new RectF(-tx / s, -ty / s, (getWidth() - tx) / s, (getHeight() - ty) / s);
    }

    /** Posisi 0..1 (0 = kiri/atas, 0,5 = tengah, 1 = kanan/bawah), untuk video. */
    float[] pos() {
        float ox = bmp.getWidth() * s - getWidth(), oy = bmp.getHeight() * s - getHeight();
        return new float[]{ox > 1 ? -tx / ox : 0.5f, oy > 1 ? -ty / oy : 0.5f};
    }
}
