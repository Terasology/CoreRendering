// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
/**
 * This package contains the Renderer's nodes.
 *
 * Nodes are added and removed to/from the RenderGraph. Each node sets its own OpenGL state via StateChange
 * objects and usually, but not necessarily, draws something in one of a number of frame buffer object.
 * The content of the FBOs is then used by other nodes and so on until the last node draws directly on the display.
 *
 * The nodes in this package are those provided by default by the engine, and include highly specific nodes (i.e.
 * ApplyDeferredLightingNode) and reusable nodes such as BlurNode.
 */
// @API
package org.terasology.corerendering.rendering.dag.nodes;

