package com.jinle.serialmonitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/** Final measurement visualization: front/side silhouettes and waist/hip ellipses. */
final class BodyContourView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private MeasurementSession.Result result;

    BodyContourView(Context context) {
        super(context);
        setBackgroundColor(Color.WHITE);
    }

    void setResult(MeasurementSession.Result result) {
        this.result = result;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (result == null || width < dp(300) || height < dp(240)) return;

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(sp(17));
        paint.setColor(Color.rgb(20, 48, 75));
        canvas.drawText("16层人体轮廓", dp(18), dp(28), paint);

        float dividerX = width * 0.58f;
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(224, 231, 239));
        canvas.drawLine(dividerX, dp(18), dividerX, height - dp(18), paint);

        float plotTop = dp(55);
        float plotBottom = height - dp(36);
        float leftCenter = dividerX * 0.27f;
        float sideCenter = dividerX * 0.73f;
        float maxBodyRadius = maxBodyRadius(result);
        float silhouetteScale = Math.min(dividerX * 0.19f / Math.max(1f, maxBodyRadius),
                (plotBottom - plotTop) / 760f);

        drawAxis(canvas, leftCenter, plotTop, plotBottom, "正面轮廓");
        drawSilhouette(canvas, leftCenter, plotTop, plotBottom, result.widthsMm,
                silhouetteScale, Color.rgb(44, 125, 184));
        drawAxis(canvas, sideCenter, plotTop, plotBottom, "侧面轮廓");
        drawSilhouette(canvas, sideCenter, plotTop, plotBottom, result.depthsMm,
                silhouetteScale, Color.rgb(45, 157, 120));
        drawHeightMarkers(canvas, dp(12), dividerX - dp(12), plotTop, plotBottom);

        float ellipseCenterX = dividerX + (width - dividerX) / 2f;
        float ellipseAreaWidth = width - dividerX - dp(30);
        float ellipseAreaHeight = (height - dp(80)) / 2f;
        drawEllipse(canvas, ellipseCenterX, dp(45) + ellipseAreaHeight * 0.48f,
                ellipseAreaWidth, ellipseAreaHeight * 0.72f, result.waistLayer,
                "腰部俯视", Color.rgb(226, 112, 56));
        drawEllipse(canvas, ellipseCenterX, dp(55) + ellipseAreaHeight * 1.46f,
                ellipseAreaWidth, ellipseAreaHeight * 0.72f, result.hipLayer,
                "臀部俯视", Color.rgb(120, 82, 170));
    }

    private void drawAxis(Canvas canvas, float centerX, float top, float bottom, String title) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.rgb(205, 215, 226));
        canvas.drawLine(centerX, top, centerX, bottom, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(sp(13));
        paint.setColor(Color.rgb(71, 89, 110));
        canvas.drawText(title, centerX, bottom + dp(24), paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSilhouette(Canvas canvas, float centerX, float top, float bottom,
                                double[] diameters, float scale, int color) {
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < diameters.length; i++) {
            if (!Double.isFinite(diameters[i])) continue;
            float y = heightToY(result.heightsMm[i], top, bottom);
            float radius = (float) diameters[i] * 0.5f * scale;
            if (!started) {
                path.moveTo(centerX - radius, y);
                started = true;
            } else {
                path.lineTo(centerX - radius, y);
            }
        }
        for (int i = diameters.length - 1; i >= 0; i--) {
            if (!Double.isFinite(diameters[i])) continue;
            float y = heightToY(result.heightsMm[i], top, bottom);
            float radius = (float) diameters[i] * 0.5f * scale;
            path.lineTo(centerX + radius, y);
        }
        if (!started) return;
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, 58));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(color);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawHeightMarkers(Canvas canvas, float left, float right, float top, float bottom) {
        if (result.waistLayer >= 0) {
            drawMarker(canvas, left, right,
                    heightToY(result.heightsMm[result.waistLayer], top, bottom),
                    "腰 " + Math.round(result.heightsMm[result.waistLayer]) + " mm",
                    Color.rgb(226, 112, 56));
        }
        if (result.hipLayer >= 0) {
            drawMarker(canvas, left, right,
                    heightToY(result.heightsMm[result.hipLayer], top, bottom),
                    "臀 " + Math.round(result.heightsMm[result.hipLayer]) + " mm",
                    Color.rgb(120, 82, 170));
        }
    }

    private void drawMarker(Canvas canvas, float left, float right, float y, String label, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(color);
        canvas.drawLine(left, y, right, y, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sp(11));
        canvas.drawText(label, left + dp(3), y - dp(3), paint);
    }

    private void drawEllipse(Canvas canvas, float centerX, float centerY, float maxWidth,
                             float maxHeight, int layer, String title, int color) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(sp(14));
        paint.setColor(Color.rgb(52, 70, 91));
        canvas.drawText(title, centerX, centerY - maxHeight * 0.55f, paint);
        if (layer < 0 || !Double.isFinite(result.widthsMm[layer]) ||
                !Double.isFinite(result.depthsMm[layer])) return;

        float scale = Math.min(maxWidth / (float) result.widthsMm[layer],
                maxHeight / (float) result.depthsMm[layer]);
        float ellipseWidth = (float) result.widthsMm[layer] * scale;
        float ellipseHeight = (float) result.depthsMm[layer] * scale;
        RectF bounds = new RectF(centerX - ellipseWidth / 2f, centerY - ellipseHeight / 2f,
                centerX + ellipseWidth / 2f, centerY + ellipseHeight / 2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, 42));
        canvas.drawOval(bounds, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(color);
        canvas.drawOval(bounds, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sp(12));
        canvas.drawText(String.format(Locale.CHINA, "宽 %.0f · 厚 %.0f mm",
                result.widthsMm[layer], result.depthsMm[layer]),
                centerX, centerY + maxHeight * 0.56f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private float heightToY(double heightMm, float top, float bottom) {
        double min = MeasurementSession.FIRST_SENSOR_HEIGHT_MM;
        double max = min + MeasurementSession.SENSOR_SPACING_MM *
                (MeasurementSession.LAYER_COUNT - 1);
        return (float) (bottom - (heightMm - min) / (max - min) * (bottom - top));
    }

    private static float maxBodyRadius(MeasurementSession.Result result) {
        double max = 1.0;
        for (double value : result.widthsMm) if (Double.isFinite(value)) max = Math.max(max, value / 2.0);
        for (double value : result.depthsMm) if (Double.isFinite(value)) max = Math.max(max, value / 2.0);
        return (float) max;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
