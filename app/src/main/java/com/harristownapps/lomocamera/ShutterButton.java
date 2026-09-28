package com.harristownapps.lomocamera;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Simple camera shutter button drawn without bitmap resources. */
public final class ShutterButton extends View {
    private static final int CAPTURE_FLASH_ALPHA = 46;
    private static final long CAPTURE_FLASH_MS = 65L;

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
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::updatePlacement);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        post(this::updatePlacement);
    }

    /**
     * In portrait the shutter sits at the physical bottom centre. When the device is
     * rotated to landscape, keep that physical position by moving the shutter to the
     * right-hand centre instead of leaving it at landscape "bottom centre".
     */
    private void updatePlacement() {
        ViewGroup.LayoutParams raw = getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        lp.leftMargin = 0;
        lp.topMargin = 0;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;

        boolean landscape = getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            lp.rightMargin = dp(30);
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(30);
        }
        setLayoutParams(lp);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean performClick() {
        // A tiny click gives immediate confirmation without needing VIBRATE permission.
        // Respect the user's system haptic setting by using the normal View API.
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        flashScreen();
        return super.performClick();
    }

    /** Very brief, low-opacity white flash over the whole camera window. */
    private void flashScreen() {
        View root = getRootView();
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;

        ColorDrawable flash = new ColorDrawable(Color.WHITE);
        flash.setAlpha(CAPTURE_FLASH_ALPHA);
        flash.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(flash);
        root.postDelayed(() -> root.getOverlay().remove(flash), CAPTURE_FLASH_MS);
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
