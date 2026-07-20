package com.partpulse.displaylauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom view that renders a PDF page bitmap directly to Canvas
 * using software rendering — no GPU texture filtering, no blur.
 */
public class PdfPageView extends View {

    private Bitmap bitmap;
    private final Paint paint;
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    public PdfPageView(Context context) {
        this(context, null);
    }

    public PdfPageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setFilterBitmap(false);     // 2x bitmap is downscaled — bilinear is sharp here
        paint.setAntiAlias(false);
        paint.setDither(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        invalidate();
    }

    public void clearBitmap() {
        this.bitmap = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        int vw = getWidth();
        int vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();

        float scaleW = (float) vw / bw;
        float scaleH = (float) vh / bh;
        float scale  = Math.min(scaleW, scaleH);
        float drawW  = bw * scale;
        float drawH  = bh * scale;
        float left   = (vw - drawW) / 2f;
        float top    = (vh - drawH) / 2f;

        srcRect.set(0, 0, bw, bh);
        dstRect.set(left, top, left + drawW, top + drawH);

        canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
    }
}
