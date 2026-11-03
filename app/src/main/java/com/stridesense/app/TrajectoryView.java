package com.stridesense.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import static com.stridesense.app.SessionData.MarkerPoint;
import static com.stridesense.app.SessionData.TrackPoint;

/** A live, scale-fitted top-down map in metres, with the printed image landmarks marked. */
final class TrajectoryView extends View {
    private final Paint background = paint(0xff0f171f, Paint.Style.FILL, 1);
    private final Paint grid = paint(0xff22313b, Paint.Style.STROKE, 1);
    private final Paint axis = paint(0xff405663, Paint.Style.STROKE, 1.2f);
    private final Paint route = paint(0xff54e2bd, Paint.Style.STROKE, 3.5f);
    private final Paint start = paint(0xff83a9ff, Paint.Style.FILL, 1);
    private final Paint current = paint(0xffffffff, Paint.Style.FILL, 1);
    private final Paint marker = paint(0xffffc15a, Paint.Style.FILL, 1);
    private final Paint markerRing = paint(0xffffc15a, Paint.Style.STROKE, 2);
    private final Paint label = paint(0xffa7b7c1, Paint.Style.FILL, 1);
    private final Paint markerLabel = paint(0xffffd18a, Paint.Style.FILL, 1);
    private List<TrackPoint> points = new ArrayList<>();
    private List<MarkerPoint> markers = new ArrayList<>();
    private final float density;

    TrajectoryView(Context context) { this(context, null); }
    TrajectoryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        route.setStrokeCap(Paint.Cap.ROUND);
        route.setStrokeJoin(Paint.Join.ROUND);
        label.setTextSize(dp(10));
        markerLabel.setTextSize(dp(9));
        markerLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        setContentDescription("Top-down indoor movement map with route and visual landmarks");
    }

    void setData(List<TrackPoint> points, List<MarkerPoint> markers) {
        this.points = new ArrayList<>(points);
        this.markers = new ArrayList<>(markers);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, background);
        float pad = dp(28);
        double minX = 0, maxX = 0, minY = 0, maxY = 0;
        for (TrackPoint p : points) { minX = Math.min(minX, p.xMeters); maxX = Math.max(maxX, p.xMeters); minY = Math.min(minY, p.yMeters); maxY = Math.max(maxY, p.yMeters); }
        for (MarkerPoint m : markers) { minX = Math.min(minX, m.xMeters); maxX = Math.max(maxX, m.xMeters); minY = Math.min(minY, m.yMeters); maxY = Math.max(maxY, m.yMeters); }
        double spanX = Math.max(2.5, maxX - minX + 1.2);
        double spanY = Math.max(2.5, maxY - minY + 1.2);
        double ppm = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
        if (ppm <= 0) return;
        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;
        float originX = (float) (w * 0.5 - centerX * ppm);
        float originY = (float) (h * 0.5 + centerY * ppm);

        for (int meter = -20; meter <= 20; meter++) {
            float x = originX + (float) (meter * ppm);
            float y = originY - (float) (meter * ppm);
            Paint p = meter == 0 ? axis : grid;
            if (x >= 0 && x <= w) canvas.drawLine(x, dp(24), x, h - dp(10), p);
            if (y >= dp(24) && y <= h - dp(10)) canvas.drawLine(dp(8), y, w - dp(8), y, p);
        }
        canvas.drawText("LOCAL MAP  ·  METRES", dp(12), dp(17), label);
        canvas.drawText("FWD", w - dp(38), dp(17), label);

        if (!points.isEmpty()) {
            Path path = new Path();
            for (int i = 0; i < points.size(); i++) {
                TrackPoint p = points.get(i);
                float px = originX + (float) (p.xMeters * ppm);
                float py = originY - (float) (p.yMeters * ppm);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            canvas.drawPath(path, route);
            TrackPoint first = points.get(0), last = points.get(points.size() - 1);
            float sx = originX + (float) (first.xMeters * ppm), sy = originY - (float) (first.yMeters * ppm);
            float ex = originX + (float) (last.xMeters * ppm), ey = originY - (float) (last.yMeters * ppm);
            canvas.drawCircle(sx, sy, dp(6), start);
            canvas.drawText("START", sx + dp(7), sy - dp(7), label);
            canvas.drawCircle(ex, ey, dp(10), haloPaint());
            canvas.drawCircle(ex, ey, dp(5), current);
            if (points.size() > 1) drawDirection(canvas, points.get(points.size() - 2), last, originX, originY, ppm);
        } else {
            canvas.drawCircle(w * 0.5f, h * 0.5f, dp(5), start);
            canvas.drawText("Start a session to map your route", dp(20), h * 0.5f + dp(28), label);
        }

        for (MarkerPoint m : markers) {
            float mx = originX + (float) (m.xMeters * ppm), my = originY - (float) (m.yMeters * ppm);
            canvas.drawCircle(mx, my, dp(8), markerRing);
            canvas.drawCircle(mx, my, dp(3.5f), marker);
            canvas.drawText(m.name, mx + dp(10), my + dp(4), markerLabel);
        }
        drawScale(canvas, ppm, w - dp(70), h - dp(18));
    }

    private void drawDirection(Canvas canvas, TrackPoint before, TrackPoint last, float ox, float oy, double ppm) {
        double dx = last.xMeters - before.xMeters, dy = last.yMeters - before.yMeters;
        double length = Math.hypot(dx, dy);
        if (length < 0.015) return;
        float x = ox + (float) (last.xMeters * ppm), y = oy - (float) (last.yMeters * ppm);
        float angle = (float) Math.atan2(-dy, dx);
        float r = dp(8);
        Path arrow = new Path();
        arrow.moveTo(x, y);
        arrow.lineTo(x - r * (float) Math.cos(angle - 0.55), y - r * (float) Math.sin(angle - 0.55));
        arrow.lineTo(x - r * (float) Math.cos(angle + 0.55), y - r * (float) Math.sin(angle + 0.55));
        arrow.close();
        canvas.drawPath(arrow, current);
    }

    private void drawScale(Canvas canvas, double ppm, float x, float y) {
        double meters = 1.0;
        if (ppm < dp(24)) meters = 2.0;
        if (ppm < dp(12)) meters = 5.0;
        float length = (float) (ppm * meters);
        canvas.drawLine(x - length, y, x, y, axis);
        canvas.drawLine(x - length, y - dp(4), x - length, y + dp(4), axis);
        canvas.drawLine(x, y - dp(4), x, y + dp(4), axis);
        canvas.drawText(String.format(java.util.Locale.US, "%.0f m", meters), x - length, y - dp(6), label);
    }

    private Paint haloPaint() { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0x4454e2bd); return p; }
    private static Paint paint(int color, Paint.Style style, float stroke) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setStyle(style); p.setStrokeWidth(stroke); return p;
    }
    private float dp(float v) { return v * density; }
}
