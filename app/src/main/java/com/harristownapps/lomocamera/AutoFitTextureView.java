package com.harristownapps.lomocamera;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

/** TextureView that preserves a requested aspect ratio. */
public final class AutoFitTextureView extends TextureView {
    private int ratioWidth;
    private int ratioHeight;

    public AutoFitTextureView(Context context) {
        super(context);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height);
            return;
        }

        if (width < height * ratioWidth / ratioHeight) {
            setMeasuredDimension(width, width * ratioHeight / ratioWidth);
        } else {
            setMeasuredDimension(height * ratioWidth / ratioHeight, height);
        }
    }
}
