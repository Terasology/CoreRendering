#version 330 core
// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

layout (location = 0) in vec3 in_vert;
layout (location = 2) in vec2 in_uv0;

out vec2 v_uv0;

void main() {
	gl_Position = vec4(in_vert, 1.0);
    v_uv0 = in_uv0;
}
