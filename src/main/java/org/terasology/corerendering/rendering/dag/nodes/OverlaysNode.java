/*
 * Copyright 2017 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.assets.ResourceUrn;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingDebugConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.ComponentSystemManager;
import org.terasology.engine.entitySystem.systems.RenderSystem;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.naming.Name;
import org.terasology.engine.rendering.cameras.SubmersibleCamera;
import org.terasology.engine.rendering.dag.WireframeCapable;
import org.terasology.engine.rendering.dag.WireframeTrigger;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.LookThrough;
import org.terasology.engine.rendering.dag.stateChanges.SetWireframe;
import org.terasology.engine.rendering.world.WorldRenderer;

/**
 * This nodes renders overlays, i.e. the black lines highlighting a nearby block the user can interact with.
 *
 * Objects to be rendered as overlays must be registered as implementing the interface RenderSystem and
 * must take advantage of the RenderSystem.renderOverlay() method, which is called in process().
 */
public class OverlaysNode extends AbstractNode implements WireframeCapable {
    private static final ResourceUrn DEFAULT_TEXTURED_MATERIAL_URN = new ResourceUrn("engine:prog.defaultTextured");

    private ComponentSystemManager componentSystemManager;
    private WorldRenderer worldRenderer;

    private SetWireframe wireframeStateChange;

    public OverlaysNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        componentSystemManager = context.get(ComponentSystemManager.class);
        addOutputBufferPairConnection(1);
        worldRenderer = context.get(WorldRenderer.class);
    }

    @Override
    public void setDependencies(Context context) {
        SubmersibleCamera playerCamera = worldRenderer.getActiveCamera();
        addDesiredStateChange(new LookThrough(playerCamera));

        wireframeStateChange = new SetWireframe(true);
        RenderingDebugConfig renderingDebugConfig = context.get(Config.class).getRendering().getDebug();
        new WireframeTrigger(renderingDebugConfig, this);

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        addOutputBufferPairConnection(1, bufferPairConnection);
        addDesiredStateChange(new BindFbo(bufferPairConnection.getBufferPair().getPrimaryFbo()));

        addDesiredStateChange(new EnableMaterial(DEFAULT_TEXTURED_MATERIAL_URN));
    }

    /**
     * Enables wireframe.
     *
     * Notice that this is just a request and wireframe gets enabled only after the
     * rendering task list has been refreshed. This occurs before the beginning
     * of next frame or earlier.
     */
    public void enableWireframe() {
        if (!getDesiredStateChanges().contains(wireframeStateChange)) {
            addDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    /**
     * Disables wireframe.
     *
     * Notice that this is just a request and wireframe gets disabled only after the
     * rendering task list has been refreshed. This occurs before the beginning
     * of next frame or earlier.
     */
    public void disableWireframe() {
        if (getDesiredStateChanges().contains(wireframeStateChange)) {
            removeDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    /**
     * Iterates over any registered RenderSystem instance and calls its renderOverlay() method.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        for (RenderSystem renderer : componentSystemManager.iterateRenderSubscribers()) {
            renderer.renderOverlay();
        }

        PerformanceMonitor.endActivity();
    }
}
