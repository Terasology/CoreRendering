#version 330 core
// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

in vec2 v_uv0;

uniform sampler2D texSceneOpaque;
uniform sampler2D texSceneOpaqueDepth;
uniform sampler2D texSceneOpaqueNormals;
uniform sampler2D texSceneOpaqueLightBuffer;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outLight;

void main() {
    vec4 colorOpaque = texture(texSceneOpaque, v_uv0.xy);
    float depthOpaque = texture(texSceneOpaqueDepth, v_uv0.xy).r * 2.0 - 1.0;
    vec4 normalBuffer = texture(texSceneOpaqueNormals, v_uv0.xy).rgba;
    vec4 lightBufferOpaque = texture(texSceneOpaqueLightBuffer, v_uv0.xy);
    vec3 blocklightColor = calcBlocklightColor(lightBufferOpaque.x);
    float sunlightIntensity = lightBufferOpaque.y;

    if (!epsilonEqualsOne(depthOpaque)) {
        // Diffuse
        colorOpaque.rgb *= blocklightColor.rgb;
#if !defined (SSAO)
        // Occlusion
        colorOpaque.rgb *= colorOpaque.a;
#endif
        // Specular
        colorOpaque.rgb += lightBufferOpaque.aaa;
    }

    outColor.rgba = colorOpaque.rgba;
    outNormal.rgba = normalBuffer.rgba;
    outLight.rgb = blocklightColor.rgb;
    outLight.a = sunlightIntensity;
    gl_FragDepth = depthOpaque * 0.5 + 0.5;
}
