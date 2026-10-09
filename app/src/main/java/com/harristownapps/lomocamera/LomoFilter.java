package com.harristownapps.lomocamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;
import android.view.TextureView;

/**
 * Camera ZOOM FX-inspired Lomo colour transform.
 *
 * The runtime transform is a compact 17x17x17 RGB LUT resampled from a 33x33x33
 * calibration LUT fitted from matched input/output photographs. It contains no Camera
 * ZOOM FX code or assets; only a colour mapping inferred from the user's own image pairs.
 */
public final class LomoFilter {
    private static final String TAG = "LomoCamera";

    public static final int LUT_SIZE = 17;

    /*
     * The saved-photo path used to perform full trilinear interpolation through the
     * 17-cube for every single photo pixel. That is accurate, but spectacularly slow
     * for a 40-50 MP image.
     *
     * Instead, pre-expand the fitted transform once into a 64x64x64 table (~1 MiB).
     * Saving a photo then needs only one array lookup per pixel. Six input bits per
     * channel means the maximum input quantisation is only four 8-bit code values,
     * while retaining the original trilinear LUT when the fast table is constructed.
     */
    private static final int FAST_BITS = 6;
    private static final int FAST_SIZE = 1 << FAST_BITS;       // 64
    private static final int FAST_MASK = FAST_SIZE - 1;
    private static final int FAST_G_SHIFT = FAST_BITS;
    private static final int FAST_R_SHIFT = FAST_BITS * 2;

    // The preview shader stays alive so the slider can change its uniform live.
    private RuntimeShader previewShader;

    private final Bitmap lutBitmap;
    private final int[] lutPixels;
    private final int lutWidth;
    private final int[] fastLut = new int[FAST_SIZE * FAST_SIZE * FAST_SIZE];

    private final int[] low = new int[256];
    private final int[] high = new int[256];
    private final float[] fraction = new float[256];

    // LUT atlas layout: x = blueSlice * LUT_SIZE + red, y = green.
    private static final String AGSL =
            "uniform shader cameraInput;\n" +
            "uniform shader lut;\n" +
            "uniform float lutN;\n" +
            "uniform float strength;\n" +
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
            "half3 finishLook(half3 c) {\n" +
            "    float3 x = float3(c);\n" +
            "    float y = dot(x, float3(0.2126, 0.7152, 0.0722));\n" +
            "    float mid = smoothstep(0.22, 0.40, y) * (1.0 - smoothstep(0.62, 0.82, y));\n" +
            "    float high = smoothstep(0.55, 0.78, y);\n" +
            "    x += float3(0.016 * mid + 0.010 * high,\n" +
            "                0.018 * mid + 0.012 * high,\n" +
            "                0.005 * mid - 0.015 * high);\n" +
            "    float lum = dot(x, float3(0.2126, 0.7152, 0.0722));\n" +
            "    float sat = 1.0 + 0.035 * smoothstep(0.18, 0.55, y);\n" +
            "    x = float3(lum) + (x - float3(lum)) * sat;\n" +
            "    return half3(clamp(x, 0.0, 1.0));\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "    half4 src = cameraInput.eval(coord);\n" +
            "    if (strength <= 0.0) return src;\n" +
            "    half3 mapped = finishLook(sampleLut(float3(src.rgb)));\n" +
            "    if (strength == 1.0) return half4(mapped, src.a);\n" +
            "    float3 blended = float3(src.rgb) + (float3(mapped) - float3(src.rgb)) * strength;\n" +
            "    return half4(half3(clamp(blended, 0.0, 1.0)), src.a);\n" +
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

        long start = System.nanoTime();
        buildFastLut();
        Log.i(TAG, String.format("Built fast 64-cube Lomo LUT in %.1f ms",
                elapsedMs(start)));
    }

    /** Apply the LUT live to a TextureView using an Android 13+ RuntimeShader. */
    public void applyToPreview(TextureView view) {
        RuntimeShader runtimeShader = new RuntimeShader(AGSL);
        BitmapShader lutShader = new BitmapShader(lutBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        lutShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        runtimeShader.setInputBuffer("lut", lutShader);
        runtimeShader.setFloatUniform("lutN", (float) LUT_SIZE);
        runtimeShader.setFloatUniform("strength", 1f);
        previewShader = runtimeShader;
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(runtimeShader, "cameraInput"));
    }

    /** Set the live preview strength; 0 = off, 100 = original, 200 = extra strong. */
    public void setPreviewStrength(int percent) {
        if (previewShader != null) {
            previewShader.setFloatUniform("strength", Math.max(0f, Math.min(2f, percent / 100f)));
        }
    }

    /** Apply the chosen strength to a captured bitmap, matching the live preview. */
    public void applyToBitmap(Bitmap bitmap, int strengthPercent) {
        if (bitmap == null) return;
        int strength = Math.max(0, Math.min(200, strengthPercent));
        if (strength == 0) return;
        // Original strength uses the existing fast LUT, preserving 1.0.1 exactly.
        // Other strengths get a temporary pre-blended table: still one lookup per pixel.
        final int[] lookup = strength == 100 ? fastLut : makeStrengthLut(strength);
        final long start = System.nanoTime();
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int chunkRows = 192;
        int[] pixels = new int[width * Math.min(chunkRows, height)];

        for (int top = 0; top < height; top += chunkRows) {
            int rows = Math.min(chunkRows, height - top);
            int count = width * rows;
            if (pixels.length < count) pixels = new int[count];
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows);
            for (int i = 0; i < count; i++) {
                int c = pixels[i];
                int r6 = (c >>> 18) & FAST_MASK;
                int g6 = (c >>> 10) & FAST_MASK;
                int b6 = (c >>> 2) & FAST_MASK;
                int mapped = lookup[(r6 << FAST_R_SHIFT) | (g6 << FAST_G_SHIFT) | b6];
                pixels[i] = (c & 0xff000000) | mapped;
            }
            bitmap.setPixels(pixels, 0, width, 0, top, width, rows);
        }

        double ms = elapsedMs(start);
        double mp = (width * (double) height) / 1_000_000.0;
        Log.i(TAG, String.format("Lomo LUT: %dx%d (%.1f MP) in %.1f ms (%.1f MP/s)",
                width, height, mp, ms, mp / Math.max(0.001, ms / 1000.0)));
    }

    /** Preblend the existing lookup with the original colour for fast photo processing. */
    private int[] makeStrengthLut(int strengthPercent) {
        int[] blended = new int[fastLut.length];
        float strength = strengthPercent / 100f;
        for (int r6 = 0; r6 < FAST_SIZE; r6++) {
            int r = representative8Bit(r6);
            for (int g6 = 0; g6 < FAST_SIZE; g6++) {
                int g = representative8Bit(g6);
                for (int b6 = 0; b6 < FAST_SIZE; b6++) {
                    int b = representative8Bit(b6);
                    int index = (r6 << FAST_R_SHIFT) | (g6 << FAST_G_SHIFT) | b6;
                    int mapped = fastLut[index];
                    int outR = clamp255(Math.round(r + (red(mapped) - r) * strength));
                    int outG = clamp255(Math.round(g + (green(mapped) - g) * strength));
                    int outB = clamp255(Math.round(b + (blue(mapped) - b) * strength));
                    blended[index] = (outR << 16) | (outG << 8) | outB;
                }
            }
        }
        return blended;
    }

    private void buildFastLut() {
        for (int r6 = 0; r6 < FAST_SIZE; r6++) {
            int r = representative8Bit(r6);
            for (int g6 = 0; g6 < FAST_SIZE; g6++) {
                int g = representative8Bit(g6);
                for (int b6 = 0; b6 < FAST_SIZE; b6++) {
                    int b = representative8Bit(b6);
                    int mapped = tuneColor(mapColorTrilinear(
                            0xff000000 | (r << 16) | (g << 8) | b));
                    fastLut[(r6 << FAST_R_SHIFT) | (g6 << FAST_G_SHIFT) | b6] =
                            mapped & 0x00ffffff;
                }
            }
        }
    }

    private static int representative8Bit(int sixBit) {
        // Centre of each four-value input bucket; keep the final bucket pinned to 255.
        return sixBit == FAST_MASK ? 255 : (sixBit << 2) + 2;
    }

    private int mapColorTrilinear(int argb) {
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

    private static int tuneColor(int argb) {
        int a = (argb >>> 24) & 0xff;
        float r = ((argb >>> 16) & 0xff) / 255f;
        float g = ((argb >>> 8) & 0xff) / 255f;
        float b = (argb & 0xff) / 255f;

        float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float mid = smoothstep(0.22f, 0.40f, y) *
                (1f - smoothstep(0.62f, 0.82f, y));
        float high = smoothstep(0.55f, 0.78f, y);

        r += 0.016f * mid + 0.010f * high;
        g += 0.018f * mid + 0.012f * high;
        b += 0.005f * mid - 0.015f * high;

        float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        float saturation = 1f + 0.035f * smoothstep(0.18f, 0.55f, y);
        r = lum + (r - lum) * saturation;
        g = lum + (g - lum) * saturation;
        b = lum + (b - lum) * saturation;

        return (a << 24) |
                (clamp255(Math.round(r * 255f)) << 16) |
                (clamp255(Math.round(g * 255f)) << 8) |
                clamp255(Math.round(b * 255f));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
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

    private static double elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000.0;
    }

    private static int red(int c) { return (c >>> 16) & 0xff; }
    private static int green(int c) { return (c >>> 8) & 0xff; }
    private static int blue(int c) { return c & 0xff; }
    private static int clamp255(int v) { return Math.max(0, Math.min(255, v)); }
}
