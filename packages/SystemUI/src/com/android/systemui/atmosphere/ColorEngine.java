package com.android.systemui.atmosphere;

import android.app.WallpaperColors;
import android.graphics.Color;

public class ColorEngine {
    
    public static int[] extractAtmosphereColors(WallpaperColors colors) {
        int[] out = new int[4];
        if (colors == null) {
            out[0] = Color.argb(255, 30, 45, 80); // Base Dark Blue
            out[1] = Color.argb(255, 255, 60, 100); // Neon Pink
            out[2] = Color.argb(255, 60, 255, 150); // Neon Green
            out[3] = Color.argb(255, 100, 150, 255); // Neon Blue
            return out;
        }
        
        int primary = colors.getPrimaryColor().toArgb();
        int secondary = colors.getSecondaryColor() != null ? colors.getSecondaryColor().toArgb() : shiftHue(primary, 45);
        int tertiary = colors.getTertiaryColor() != null ? colors.getTertiaryColor().toArgb() : shiftHue(primary, -45);
        
        // [0] BASE BLOB: Deep, rich version of primary color
        out[0] = createBaseColor(primary);
        
        // [1,2,3] FLOATING BLOBS: Highly vibrant, pure neon colors
        out[1] = createBlobColor(primary);
        out[2] = createBlobColor(secondary);
        out[3] = createBlobColor(tertiary);
                 
        return out;
    }

    private static int createBaseColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1.0f, hsv[1] * 1.2f); // Rich saturation
        hsv[2] = Math.max(0.2f, Math.min(0.4f, hsv[2])); // Deep brightness
        return Color.HSVToColor(255, hsv); // 100% Opaque
    }

    private static int createBlobColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1.0f, hsv[1] * 1.8f); // Extreme saturation for "Color Pop"
        hsv[2] = Math.min(1.0f, hsv[2] * 1.5f); // High brightness
        return Color.HSVToColor(255, hsv); // 100% Opaque
    }

    private static int shiftHue(int color, float shift) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + shift + 360) % 360;
        return Color.HSVToColor(255, hsv);
    }
}
