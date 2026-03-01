package com.android.systemui.atmosphere;

import android.app.WallpaperColors;
import android.graphics.Color;

public class ColorEngine {
    
    public static int[] extractAtmosphereColors(WallpaperColors colors) {
        int[] out = new int[4];
        if (colors == null) {
            // Soft translucent fallback colors
            out[0] = Color.argb(120, 15, 15, 20); 
            out[1] = Color.argb(100, 255, 60, 100); 
            out[2] = Color.argb(100, 60, 255, 150); 
            out[3] = Color.argb(100, 100, 150, 255); 
            return out;
        }
        
        int primary = colors.getPrimaryColor().toArgb();
        int secondary = colors.getSecondaryColor() != null ? colors.getSecondaryColor().toArgb() : shiftHue(primary, 45);
        int tertiary = colors.getTertiaryColor() != null ? colors.getTertiaryColor().toArgb() : shiftHue(primary, -45);
        
        out[0] = createBaseColor(primary);
        out[1] = createBlobColor(primary);
        out[2] = createBlobColor(secondary);
        out[3] = createBlobColor(tertiary);
                 
        return out;
    }

    private static int createBaseColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(0.8f, hsv[1]); 
        hsv[2] = Math.min(0.2f, hsv[2]); 
        // 140 Alpha (55% opacity) -> Lets the blurred wallpaper show through!
        return Color.HSVToColor(140, hsv);
    }

    private static int createBlobColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0.6f, Math.min(1.0f, hsv[1] * 1.5f)); 
        hsv[2] = Math.max(0.4f, Math.min(0.7f, hsv[2] * 1.2f)); 
        // 90 Alpha -> Adds soft, vibrant glowing areas without destroying the image
        return Color.HSVToColor(90, hsv); 
    }

    private static int shiftHue(int color, float shift) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + shift + 360) % 360;
        return Color.HSVToColor(90, hsv);
    }
}
