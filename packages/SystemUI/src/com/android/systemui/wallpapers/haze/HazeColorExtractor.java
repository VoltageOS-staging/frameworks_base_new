/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.wallpapers.haze;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Collections;
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
      this.color = color;
      this.x = x;
      this.y = y;
    }
  }

  public static List<ColorCluster> extractColors(Bitmap original, int targetColors) {
    Bitmap bitmap = original;
    boolean didScale = false;

    if (original.getWidth() > 128 || original.getHeight() > 128) {
      float scale = Math.min(128f / original.getWidth(), 128f / original.getHeight());
      bitmap =
          Bitmap.createScaledBitmap(
              original,
              Math.max(1, Math.round(original.getWidth() * scale)),
              Math.max(1, Math.round(original.getHeight() * scale)),
              true);
      didScale = true;
    }

    int w = bitmap.getWidth();
    int h = bitmap.getHeight();

    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

    List<ColorPoint> samples = new ArrayList<>();

    int stepX = Math.max(1, w / 32);
    int stepY = Math.max(1, h / 32);

    for (int y = 0; y < h; y += stepY) {
      for (int x = 0; x < w; x += stepX) {
        samples.add(new ColorPoint(pixels[y * w + x], x, y));
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
          int r = Color.red(p.color);
          int g = Color.green(p.color);
          int b = Color.blue(p.color);
          if (r < minR) minR = r;
          if (r > maxR) maxR = r;
          if (g < minG) minG = g;
          if (g > maxG) maxG = g;
          if (b < minB) minB = b;
          if (b > maxB) maxB = b;
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
      Collections.sort(
          largestBucket,
          (p1, p2) -> {
            if (channel == 0) return Integer.compare(Color.red(p1.color), Color.red(p2.color));
            if (channel == 1) return Integer.compare(Color.green(p1.color), Color.green(p2.color));
            return Integer.compare(Color.blue(p1.color), Color.blue(p2.color));
          });

      int median = largestBucket.size() / 2;
      List<ColorPoint> bucket1 = new ArrayList<>(largestBucket.subList(0, median));
      List<ColorPoint> bucket2 =
          new ArrayList<>(largestBucket.subList(median, largestBucket.size()));
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
        sumR += Color.red(p.color);
        sumG += Color.green(p.color);
        sumB += Color.blue(p.color);
        sumX += p.x;
        sumY += p.y;
      }
      int count = bucket.size();
      int avgColor = Color.rgb((int) (sumR / count), (int) (sumG / count), (int) (sumB / count));
      clusters.add(new ColorCluster(avgColor, sumX / count / w, sumY / count / h));
    }

    if (didScale) {
      bitmap.recycle();
    }

    return clusters;
  }
}
