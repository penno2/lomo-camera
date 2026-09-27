package com.example.lomocamera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Minimal focus reticle: appears where the user taps, then fades away. */
public final class FocusIndicatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float x = -1f;
    private float y = -1f;
    private float alpha = 0f;
    private final float radius;
    private ValueAnimator animator;

    public FocusIndicatorView(Context context) {
        super(context);
        radius = 28f * getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        paint.setColor(Color.WHITE);
        setClickable(false);
        setFocusable(false);
    }

    public void showAt(float x, float y) {
        this.x = x;
        this.y = y;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(1f, 1f, 0f);
        animator.setDuration(850);
        animator.addUpdateListener(a -> {
            alpha = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (alpha <= 0f || x < 0f || y < 0f) return;
        paint.setAlpha(Math.round(255f * alpha));
        canvas.drawCircle(x, y, radius, paint);
        float tick = radius * 0.35f;
        canvas.drawLine(x - radius, y, x - radius + tick, y, paint);
        canvas.drawLine(x + radius - tick, y, x + radius, y, paint);
        canvas.drawLine(x, y - radius, x, y - radius + tick, paint);
        canvas.drawLine(x, y + radius - tick, x, y + radius, paint);
    }
}
