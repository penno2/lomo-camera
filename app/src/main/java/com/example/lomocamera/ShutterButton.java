package com.example.lomocamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Simple camera shutter button drawn without bitmap resources. */
public final class ShutterButton extends View {
    private final Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShutterButton(Context context) {
        super(context);
        outer.setColor(Color.WHITE);
        outer.setStyle(Paint.Style.STROKE);
        outer.setStrokeWidth(4f * getResources().getDisplayMetrics().density);
        inner.setColor(Color.WHITE);
        inner.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Take photo");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.42f;
        float pressedScale = isPressed() ? 0.87f : 1f;
        float enabledAlpha = isEnabled() ? 1f : 0.38f;
        outer.setAlpha(Math.round(255f * enabledAlpha));
        inner.setAlpha(Math.round(255f * enabledAlpha));
        canvas.drawCircle(cx, cy, r, outer);
        canvas.drawCircle(cx, cy, r * 0.72f * pressedScale, inner);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        invalidate();
    }
}
