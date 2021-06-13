// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingDebugConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.ComponentSystemManager;
import org.terasology.engine.entitySystem.systems.RenderSystem;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.dag.WireframeCapable;
import org.terasology.engine.rendering.dag.WireframeTrigger;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
import org.terasology.engine.rendering.dag.stateChanges.SetWireframe;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.gestalt.naming.Name;

/**
 * This node renders the opaque (as opposed to semi-transparent)
 * objects present in the world. This node -does not- render the landscape.
 *
 * Objects to be rendered must be registered as implementing the interface RenderSystem and
 * take advantage of the RenderSystem.renderOpaque() method, which is called in process().
 */
public class OpaqueObjectsNode extends AbstractNode implements WireframeCapable {
    private ComponentSystemManager componentSystemManager;
    private WorldRenderer worldRenderer;

    private SetWireframe wireframeStateChange;
    private EnableFaceCulling faceCullingStateChange;

    public OpaqueObjectsNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        componentSystemManager = context.get(ComponentSystemManager.class);

        worldRenderer = context.get(WorldRenderer.class);

        faceCullingStateChange = new EnableFaceCulling();

        wireframeStateChange = new SetWireframe(true);
        RenderingDebugConfig renderingDebugConfig = context.get(Config.class).getRendering().getDebug();
        new WireframeTrigger(renderingDebugConfig, this);

        addOutputBufferPairConnection(1);
    }

    @Override
    public void setDependencies(Context context) {

        // IF wireframe is enabled the WireframeTrigger will remove the face culling state change
        // from the set of desired state changes.
        // The alternative would have been to check here first if wireframe mode is enabled and *if not*
        // add the face culling state change. However, if wireframe *is* enabled, the WireframeTrigger
        // would attempt to remove the face culling state even though it isn't there, relying on the
        // quiet behaviour of Set.remove(nonExistentItem). We therefore favored the first solution.

        addDesiredStateChange(faceCullingStateChange);

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        addOutputBufferPairConnection(1, bufferPairConnection);
        addDesiredStateChange(new BindFbo(bufferPairConnection.getBufferPair().getPrimaryFbo()));
    }

    public void enableWireframe() {
        if (!getDesiredStateChanges().contains(wireframeStateChange)) {
            removeDesiredStateChange(faceCullingStateChange);
            addDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    public void disableWireframe() {
        if (getDesiredStateChanges().contains(wireframeStateChange)) {
            addDesiredStateChange(faceCullingStateChange);
            removeDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    /**
     * Iterates over any registered RenderSystem instance and calls its renderOpaque() method.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        for (RenderSystem renderer : componentSystemManager.iterateRenderSubscribers()) {
            renderer.renderOpaque();
        }

        PerformanceMonitor.endActivity();
    }
}
