package com.example.lomocamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.TextureView;

/**
 * Camera ZOOM FX-inspired Lomo colour transform.
 *
 * The runtime transform is a compact 17x17x17 RGB LUT resampled from a 33x33x33
 * calibration LUT fitted from matched input/output photographs. It contains no Camera ZOOM FX code or assets; only a colour mapping
 * inferred from the user's own image pairs.
 */
public final class LomoFilter {
    public static final int LUT_SIZE = 17;

    private final Bitmap lutBitmap;
    private final int[] lutPixels;
    private final int lutWidth;

    private final int[] low = new int[256];
    private final int[] high = new int[256];
    private final float[] fraction = new float[256];

    // LUT atlas layout: x = blueSlice * LUT_SIZE + red, y = green.
    private static final String AGSL =
            "uniform shader cameraInput;\n" +
            "uniform shader lut;\n" +
            "uniform float lutN;\n" +
            "\n" +
            "half3 sampleLut(float3 rgb) {\n" +
            "    float3 p = clamp(rgb, 0.0, 1.0) * (lutN - 1.0);\n" +
            "    float3 lo = floor(p);\n" +
            "    float3 hi = min(lo + 1.0, lutN - 1.0);\n" +
            "    float3 f = p - lo;\n" +
            "\n" +
            "    float2 p000 = float2(lo.z * lutN + lo.x + 0.5, lo.y + 0.5);\n" +
            "    float2 p100 = float2(lo.z * lutN + hi.x + 0.5, lo.y + 0.5);\n" +
            "    float2 p010 = float2(lo.z * lutN + lo.x + 0.5, hi.y + 0.5);\n" +
            "    float2 p110 = float2(lo.z * lutN + hi.x + 0.5, hi.y + 0.5);\n" +
            "    float2 p001 = float2(hi.z * lutN + lo.x + 0.5, lo.y + 0.5);\n" +
            "    float2 p101 = float2(hi.z * lutN + hi.x + 0.5, lo.y + 0.5);\n" +
            "    float2 p011 = float2(hi.z * lutN + lo.x + 0.5, hi.y + 0.5);\n" +
            "    float2 p111 = float2(hi.z * lutN + hi.x + 0.5, hi.y + 0.5);\n" +
            "\n" +
            "    half3 c000 = lut.eval(p000).rgb;\n" +
            "    half3 c100 = lut.eval(p100).rgb;\n" +
            "    half3 c010 = lut.eval(p010).rgb;\n" +
            "    half3 c110 = lut.eval(p110).rgb;\n" +
            "    half3 c001 = lut.eval(p001).rgb;\n" +
            "    half3 c101 = lut.eval(p101).rgb;\n" +
            "    half3 c011 = lut.eval(p011).rgb;\n" +
            "    half3 c111 = lut.eval(p111).rgb;\n" +
            "\n" +
            "    half3 c00 = mix(c000, c100, half(f.x));\n" +
            "    half3 c10 = mix(c010, c110, half(f.x));\n" +
            "    half3 c01 = mix(c001, c101, half(f.x));\n" +
            "    half3 c11 = mix(c011, c111, half(f.x));\n" +
            "    half3 c0 = mix(c00, c10, half(f.y));\n" +
            "    half3 c1 = mix(c01, c11, half(f.y));\n" +
            "    return mix(c0, c1, half(f.z));\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    half4 src = cameraInput.eval(coord);\n" +
            "    half3 mapped = sampleLut(float3(src.rgb));\n" +
            "    return half4(mapped, src.a);\n" +
            "}\n";

    public LomoFilter(Context context) {
        lutBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.lomo_lut);
        if (lutBitmap == null || lutBitmap.getWidth() != LUT_SIZE * LUT_SIZE ||
                lutBitmap.getHeight() != LUT_SIZE) {
            throw new IllegalStateException("Invalid Lomo LUT asset");
        }
        lutWidth = lutBitmap.getWidth();
        lutPixels = new int[lutWidth * lutBitmap.getHeight()];
        lutBitmap.getPixels(lutPixels, 0, lutWidth, 0, 0, lutWidth, lutBitmap.getHeight());

        for (int v = 0; v < 256; v++) {
            float p = (v / 255f) * (LUT_SIZE - 1);
            int l = (int) Math.floor(p);
            if (l >= LUT_SIZE - 1) l = LUT_SIZE - 2;
            low[v] = l;
            high[v] = l + 1;
            fraction[v] = Math.max(0f, Math.min(1f, p - l));
        }
    }

    /** Apply the LUT live to a TextureView using an Android 13+ RuntimeShader. */
    public void applyToPreview(TextureView view) {
        RuntimeShader runtimeShader = new RuntimeShader(AGSL);
        BitmapShader lutShader = new BitmapShader(lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        lutShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        runtimeShader.setInputBuffer("lut", lutShader);
        runtimeShader.setFloatUniform("lutN", (float) LUT_SIZE);
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(runtimeShader, "cameraInput"));
    }

    /** Apply the identical LUT to a captured bitmap in-place. */
    public void applyToBitmap(Bitmap bitmap) {
        if (bitmap == null) return;
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int chunkRows = 96;
        int[] pixels = new int[width * Math.min(chunkRows, height)];

        for (int top = 0; top < height; top += chunkRows) {
            int rows = Math.min(chunkRows, height - top);
            int count = width * rows;
            if (pixels.length < count) pixels = new int[count];
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows);
            for (int i = 0; i < count; i++) {
                pixels[i] = mapColor(pixels[i]);
            }
            bitmap.setPixels(pixels, 0, width, 0, top, width, rows);
        }
    }

    private int mapColor(int argb) {
        int a = (argb >>> 24) & 0xff;
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;

        int r0 = low[r], r1 = high[r];
        int g0 = low[g], g1 = high[g];
        int b0 = low[b], b1 = high[b];
        float fr = fraction[r], fg = fraction[g], fb = fraction[b];

        int c000 = lut(r0, g0, b0);
        int c100 = lut(r1, g0, b0);
        int c010 = lut(r0, g1, b0);
        int c110 = lut(r1, g1, b0);
        int c001 = lut(r0, g0, b1);
        int c101 = lut(r1, g0, b1);
        int c011 = lut(r0, g1, b1);
        int c111 = lut(r1, g1, b1);

        int outR = Math.round(trilerp(
                red(c000), red(c100), red(c010), red(c110),
                red(c001), red(c101), red(c011), red(c111), fr, fg, fb));
        int outG = Math.round(trilerp(
                green(c000), green(c100), green(c010), green(c110),
                green(c001), green(c101), green(c011), green(c111), fr, fg, fb));
        int outB = Math.round(trilerp(
                blue(c000), blue(c100), blue(c010), blue(c110),
                blue(c001), blue(c101), blue(c011), blue(c111), fr, fg, fb));

        return (a << 24) | (clamp255(outR) << 16) | (clamp255(outG) << 8) | clamp255(outB);
    }

    private int lut(int r, int g, int b) {
        int x = b * LUT_SIZE + r;
        return lutPixels[g * lutWidth + x];
    }

    private static float trilerp(float c000, float c100, float c010, float c110,
                                 float c001, float c101, float c011, float c111,
                                 float fx, float fy, float fz) {
        float c00 = c000 + (c100 - c000) * fx;
        float c10 = c010 + (c110 - c010) * fx;
        float c01 = c001 + (c101 - c001) * fx;
        float c11 = c011 + (c111 - c011) * fx;
        float c0 = c00 + (c10 - c00) * fy;
        float c1 = c01 + (c11 - c01) * fy;
        return c0 + (c1 - c0) * fz;
    }

    private static int red(int c) { return (c >>> 16) & 0xff; }
    private static int green(int c) { return (c >>> 8) & 0xff; }
    private static int blue(int c) { return c & 0xff; }
    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
}
