package com.unclebanana.pulseone.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small dependency-free line chart for recent heart-rate trends. */
public final class VitalChartView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Float> values = List.of();

    public VitalChartView(Context context) { this(context, null); }

    public VitalChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        grid.setColor(Color.rgb(45, 63, 77));
        grid.setStrokeWidth(dp(1));
        line.setColor(Color.rgb(56, 214, 199));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2.5f));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(Color.rgb(174, 187, 197));
        label.setTextSize(dp(12));
        setContentDescription("กราฟอัตราการเต้นหัวใจล่าสุด");
    }

    public void setValues(List<Float> newValues) {
        values = new ArrayList<>(newValues == null ? List.of() : newValues);
        if (!values.isEmpty()) {
            float last = values.get(values.size() - 1);
            setContentDescription(String.format(Locale.getDefault(),
                    "กราฟอัตราการเต้นหัวใจ %d จุด ค่าล่าสุด %.0f ครั้งต่อนาที", values.size(), last));
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(38), top = dp(14), right = getWidth() - dp(10), bottom = getHeight() - dp(26);
        if (right <= left || bottom <= top) return;

        for (int i = 0; i <= 3; i++) {
            float y = top + (bottom - top) * i / 3f;
            canvas.drawLine(left, y, right, y, grid);
        }
        if (values.size() < 2) {
            canvas.drawText("รอข้อมูลอย่างน้อย 2 จุด", left, (top + bottom) / 2, label);
            return;
        }

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float value : values) { min = Math.min(min, value); max = Math.max(max, value); }
        min = Math.max(30, min - 5);
        max = Math.min(220, max + 5);
        if (max - min < 10) max = min + 10;

        canvas.drawText(String.format(Locale.getDefault(), "%.0f", max), dp(3), top + dp(5), label);
        canvas.drawText(String.format(Locale.getDefault(), "%.0f", min), dp(3), bottom, label);

        Path path = new Path();
        for (int i = 0; i < values.size(); i++) {
            float x = left + (right - left) * i / (values.size() - 1f);
            float y = bottom - (values.get(i) - min) / (max - min) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
