package com.android.systemui.atmosphere;

import android.app.WallpaperColors;
import android.graphics.Color;

public class ColorEngine {
    
    public static int extractAtmosphereColor(WallpaperColors colors) {
        if (colors == null) return Color.argb(255, 100, 100, 100);
        
        int primary = colors.getPrimaryColor().toArgb();
        float[] hsv = new float[3];
        Color.colorToHSV(primary, hsv);
        
        // Rule 4: Luminance Filtering & Vibrancy Weighting
        // Limit saturation to strictly < 60% so blobs look like light, not stickers.
        hsv[1] = Math.min(0.55f, hsv[1]); 
        
        // Filter extreme highlights/shadows
        hsv[2] = Math.max(0.3f, Math.min(0.85f, hsv[2])); 
        
        return Color.HSVToColor(hsv);
    }
}
