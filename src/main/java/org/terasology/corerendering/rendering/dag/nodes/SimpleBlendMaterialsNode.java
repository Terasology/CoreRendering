// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.context.Context;
import org.terasology.engine.core.ComponentSystemManager;
import org.terasology.engine.entitySystem.systems.RenderSystem;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.gestalt.naming.Name;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.DisableDepthWriting;
import org.terasology.engine.rendering.dag.stateChanges.EnableBlending;
import org.terasology.engine.rendering.dag.stateChanges.SetBlendFunction;

import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;

/**
 * An instance of this class renders and blends semi-transparent objects into the content of the existing g-buffer.
 *
 * Notice that this is handled in the process() method by calling the renderAlphaBlend() method of registered
 * instances implementing the RenderSystem interface.
 *
 * Theoretically the same results could be achieved by rendering all meshes in one go, keeping blending
 * always enabled and relying on the alpha channel of the textures associated with a given mesh. In practice
 * blending is an expensive operation and it wouldn't be good performance-wise to keep it always enabled.
 *
 * Also, a number of previous nodes rely on unambiguous meaning for the depth values in the gbuffers,
 * but this node temporarily disable writing to the depth buffer - what value should be written to it,
 * the distance to the semi-transparent surface or what's already stored in the depth buffer? As such
 * semi-transparent objects are handled here, after nodes relying on the depth buffer have done their job.
 */
public class SimpleBlendMaterialsNode extends AbstractNode {
    private ComponentSystemManager componentSystemManager;

    public SimpleBlendMaterialsNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        componentSystemManager = context.get(ComponentSystemManager.class);
        addOutputFboConnection(1);
        addOutputBufferPairConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        addOutputBufferPairConnection(1, bufferPairConnection);
        addOutputFboConnection(1, bufferPairConnection.getBufferPair().getPrimaryFbo());
        addDesiredStateChange(new BindFbo(this.getOutputFboData(1)));

        // Sets the state for the rendering of objects or portions of objects having some degree of transparency.
        // Generally speaking objects drawn with this state will have their color blended with the background
        // color, depending on their opacity. I.e. a 25% opaque foreground object will provide 25% of its
        // color while the background will provide the remaining 75%. The sum of the two RGBA vectors gets
        // written onto the output buffer.
        addDesiredStateChange(new EnableBlending());

        // (*) In this context SRC is Foreground. This effectively says:
        // Resulting RGB = ForegroundRGB * ForegroundAlpha + BackgroundRGB * (1 - ForegroundAlpha)
        // Which might still look complicated, but it's actually the most typical alpha-driven composite.
        // A neat tool to play with this settings can be found here: http://www.andersriggelsen.dk/glblendfunc.php
        addDesiredStateChange(new SetBlendFunction(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA));

        // Important note: the following disables writing to the Depth Buffer. This is why filters relying on
        // depth information (i.e. DoF) have problems with transparent objects: the depth of their pixels is
        // found to be that of the background rather than that of the transparent's object surface.
        // This is an unresolved (unresolv-able?) issue that would only be reversed, not eliminated,
        // by re-enabling writing to the Depth Buffer.
        addDesiredStateChange(new DisableDepthWriting());
    }

    /**
     * Iterates over registered RenderSystem instances and call their renderAlphaBlend() method.
     *
     * This leaves great freedom to RenderSystem implementations, but also the responsibility to
     * leave the OpenGL state in the way they found it - otherwise the next system or the next
     * render node might not be able to function properly.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        for (RenderSystem renderer : componentSystemManager.iterateRenderSubscribers()) {
            renderer.renderAlphaBlend();
        }

        PerformanceMonitor.endActivity();
    }
}
