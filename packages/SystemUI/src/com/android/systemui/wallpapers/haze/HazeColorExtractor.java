package com.android.systemui.wallpapers.haze;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class HazeColorExtractor {

    public static class ColorCluster {
        public int color;
        public float centerX;
        public float centerY;
        public ColorCluster(int color, float centerX, float centerY) {
            this.color = color;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    private static class ColorPoint {
        int color, x, y;
        ColorPoint(int color, int x, int y) {
            this.color = color; this.x = x; this.y = y;
        }
    }

    public static List<ColorCluster> extractColors(Bitmap bitmap, int targetColors) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        List<ColorPoint> samples = new ArrayList<>();
        int step = 10;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                samples.add(new ColorPoint(bitmap.getPixel(x, y), x, y));
            }
        }

        List<List<ColorPoint>> buckets = new ArrayList<>();
        buckets.add(samples);

        while (buckets.size() < targetColors) {
            List<ColorPoint> largestBucket = null;
            int largestRange = 0;
            int splitChannel = 0;

            for (List<ColorPoint> bucket : buckets) {
                if (bucket.size() <= 1) continue;
                int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
                for (ColorPoint p : bucket) {
                    int r = Color.red(p.color); int g = Color.green(p.color); int b = Color.blue(p.color);
                    if (r < minR) minR = r; if (r > maxR) maxR = r;
                    if (g < minG) minG = g; if (g > maxG) maxG = g;
                    if (b < minB) minB = b; if (b > maxB) maxB = b;
                }
                int rRange = maxR - minR, gRange = maxG - minG, bRange = maxB - minB;
                int maxRange = Math.max(rRange, Math.max(gRange, bRange));
                if (maxRange > largestRange) {
                    largestRange = maxRange;
                    largestBucket = bucket;
                    splitChannel = (maxRange == rRange) ? 0 : (maxRange == gRange) ? 1 : 2;
                }
            }
            if (largestBucket == null) break;

            final int channel = splitChannel;
            Collections.sort(largestBucket, new Comparator<ColorPoint>() {
                @Override
                public int compare(ColorPoint p1, ColorPoint p2) {
                    if (channel == 0) return Integer.compare(Color.red(p1.color), Color.red(p2.color));
                    if (channel == 1) return Integer.compare(Color.green(p1.color), Color.green(p2.color));
                    return Integer.compare(Color.blue(p1.color), Color.blue(p2.color));
                }
            });

            int median = largestBucket.size() / 2;
            List<ColorPoint> bucket1 = new ArrayList<>(largestBucket.subList(0, median));
            List<ColorPoint> bucket2 = new ArrayList<>(largestBucket.subList(median, largestBucket.size()));
            buckets.remove(largestBucket);
            buckets.add(bucket1);
            buckets.add(bucket2);
        }

        List<ColorCluster> clusters = new ArrayList<>();
        for (List<ColorPoint> bucket : buckets) {
            if (bucket.isEmpty()) continue;
            long sumR = 0, sumG = 0, sumB = 0;
            float sumX = 0, sumY = 0;
            for (ColorPoint p : bucket) {
                sumR += Color.red(p.color); sumG += Color.green(p.color); sumB += Color.blue(p.color);
                sumX += p.x; sumY += p.y;
            }
            int count = bucket.size();
            int avgColor = Color.rgb((int)(sumR/count), (int)(sumG/count), (int)(sumB/count));
            clusters.add(new ColorCluster(avgColor, sumX/count/w, sumY/count/h));
        }
        return clusters;
    }
}
