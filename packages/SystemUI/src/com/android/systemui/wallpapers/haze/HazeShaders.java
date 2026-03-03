package com.android.systemui.wallpapers.haze;

public class HazeShaders {
    public static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "in vec4 aPosition;\n" +
            "in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    public static final String BLUR_FRAGMENT_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 vTexCoord;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform vec2 uDirection;\n" +
            "uniform float uRadius;\n" +
            "void main() {\n" +
            "    vec2 texelSize = 1.0 / vec2(textureSize(uTexture, 0));\n" +
            "    vec3 result = vec3(0.0);\n" +
            "    float totalWeight = 0.0;\n" +
            "    for(float i = -uRadius; i <= uRadius; i++) {\n" +
            "        vec2 offset = uDirection * i * texelSize;\n" +
            "        float weight = 1.0 - abs(i) / uRadius;\n" +
            "        result += texture(uTexture, vTexCoord + offset).rgb * weight;\n" +
            "        totalWeight += weight;\n" +
            "    }\n" +
            "    fragColor = vec4(result / totalWeight, 1.0);\n" +
            "}\n";

    public static final String HAZE_FRAGMENT_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 vTexCoord;\n" +
            "out vec4 fragColor;\n" +
            "uniform sampler2D uTextureSharp;\n" +
            "uniform sampler2D uTextureBlur;\n" +
            "#define MAX_BLOBS 16\n" +
            "uniform vec3 uBlobColors[MAX_BLOBS];\n" +
            "uniform vec2 uBlobPositions[MAX_BLOBS];\n" +
            "uniform float uBlobSizes[MAX_BLOBS];\n" +
            "uniform int uBlobCount;\n" +
            "uniform float uAspectRatio;\n" +
            "uniform float uBlurStrength;\n" +
            "uniform float uDimLevel;\n" +
            "uniform int uStyle;\n" +
            "float random(vec2 co) {\n" +
            "    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);\n" +
            "}\n" +
            "void main() {\n" +
            "    float t = clamp(uBlurStrength, 0.0, 1.0);\n" +
            "    vec2 uv = vTexCoord;\n" +
            "    vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;\n" +
            "    vec3 finalColor;\n" +
            "    \n" +
            "    if (uStyle == 0 || uStyle == 1 || uStyle == 4) {\n" +
            "        uv.x *= uAspectRatio;\n" +
            "        vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;\n" +
            "        finalColor = mix(sharp, frosted, smoothstep(0.0, 0.2, t));\n" +
            "        vec3 cloudSum = vec3(0.0);\n" +
            "        float cloudWeight = 0.0;\n" +
            "        for(int i = 0; i < MAX_BLOBS; i++) {\n" +
            "            if (i >= uBlobCount) break;\n" +
            "            vec2 pos = uBlobPositions[i];\n" +
            "            pos.x *= uAspectRatio;\n" +
            "            float dist = length(uv - pos);\n" +
            "            float w = uBlobSizes[i] / (pow(dist, 2.0) + 0.05);\n" +
            "            cloudSum += uBlobColors[i] * w;\n" +
            "            cloudWeight += w;\n" +
            "        }\n" +
            "        if (cloudWeight > 0.0 && t > 0.18) {\n" +
            "            finalColor = mix(finalColor, cloudSum / cloudWeight, smoothstep(0.18, 0.5, t));\n" +
            "        }\n" +
            "        float blobOpacity = smoothstep(0.15, 0.3, t);\n" +
            "        if (blobOpacity > 0.01 && uBlobCount > 0) {\n" +
            "            for(int i = 0; i < MAX_BLOBS; i++) {\n" +
            "                if (i >= uBlobCount) break;\n" +
            "                vec2 pos = uBlobPositions[i];\n" +
            "                pos.x *= uAspectRatio;\n" +
            "                float dist = length(uv - pos);\n" +
            "                float alpha = (1.0 - smoothstep(0.0, uBlobSizes[i], dist)) * blobOpacity;\n" +
            "                if (alpha > 0.0) {\n" +
            "                    finalColor = mix(finalColor, uBlobColors[i], alpha);\n" +
            "                }\n" +
            "            }\n" +
            "        }\n" +
            "    } else if (uStyle == 5) {\n" +
            "        // Vertical Melt Effect\n" +
            "        float n1 = random(vec2(floor(vTexCoord.x * 120.0), 0.0));\n" +
            "        float n2 = random(vec2(floor(vTexCoord.x * 20.0), 0.0));\n" +
            "        float drip = t * (0.2 + 0.8 * n1) * (0.5 + 0.5 * n2) * 1.5;\n" +
            "        vec3 colorSum = vec3(0.0);\n" +
            "        for(int i = 0; i < 3; i++) {\n" +
            "            float offset = float(i) * 0.01 * t;\n" +
            "            vec2 sampleUV = vTexCoord;\n" +
            "            sampleUV.y = max(0.0, sampleUV.y - drip - offset);\n" +
            "            colorSum += texture(uTextureSharp, sampleUV).rgb;\n" +
            "        }\n" +
            "        finalColor = colorSum / 3.0;\n" +
            "    } else {\n" +
            "        // Frosted Ice Crystallization Effect\n" +
            "        vec2 scatter = vec2(random(vTexCoord) - 0.5, random(vTexCoord + 0.5) - 0.5) * 0.05 * t;\n" +
            "        vec3 sharpScatter = texture(uTextureSharp, vTexCoord + scatter).rgb;\n" +
            "        vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;\n" +
            "        finalColor = mix(sharpScatter, frosted, smoothstep(0.1, 0.7, t));\n" +
            "        float ice = random(floor(vTexCoord * 300.0));\n" +
            "        float icePeak = sin(t * 3.14159);\n" + // Peak intensity in middle of animation
            "        finalColor += vec3(ice * 0.15 * icePeak);\n" +
            "    }\n" +
            "    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);\n" +
            "    float noise = random(floor(vTexCoord * 2000.0));\n" +
            "    finalColor += vec3(noise * 0.06 * smoothstep(0.0, 0.4, t));\n" +
            "    fragColor = vec4(finalColor, 1.0);\n" +
            "}\n";
}
