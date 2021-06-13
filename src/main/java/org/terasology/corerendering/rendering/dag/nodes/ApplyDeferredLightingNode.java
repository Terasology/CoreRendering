// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.gestalt.naming.Name;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.dependencyConnections.DependencyConnection;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.LightAccumulationTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.NormalsTexture;

/**
 * The ApplyDeferredLightingNode takes advantage of the information stored by previous nodes
 * in various buffers, especially the light accumulation buffer and lights up the otherwise
 * flatly-lit 3d scene.
 *
 * This node is integral to the deferred lighting technique.
 */
public class ApplyDeferredLightingNode extends AbstractNode {
    private static final ResourceUrn DEFERRED_LIGHTING_MATERIAL_URN = new ResourceUrn("CoreRendering:lightBufferPass");
    private Mesh renderQuad;

    public ApplyDeferredLightingNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);
        addOutputBufferPairConnection(1);

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        // Add new instance of swapped bufferPair as output
        addOutputBufferPairConnection(1, bufferPairConnection.getSwappedCopy(DependencyConnection.Type.OUTPUT, this.getUri()));

        DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
        // SwappableFBO gBufferPair = displayResolutionDependentFBOs.getGBufferPair();

        addDesiredStateChange(new BindFbo(bufferPairConnection.getBufferPair().getSecondaryFbo()));

        addDesiredStateChange(new EnableMaterial(DEFERRED_LIGHTING_MATERIAL_URN));

        // FBO lastUpdatedGBuffer = displayResolutionDependentFBOs.getGBufferPair().getLastUpdatedFbo();

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, ColorTexture,
                displayResolutionDependentFBOs, DEFERRED_LIGHTING_MATERIAL_URN, "texSceneOpaque"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, DepthStencilTexture,
                displayResolutionDependentFBOs, DEFERRED_LIGHTING_MATERIAL_URN, "texSceneOpaqueDepth"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, NormalsTexture,
                displayResolutionDependentFBOs, DEFERRED_LIGHTING_MATERIAL_URN, "texSceneOpaqueNormals"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot,   lastUpdatedGBuffer, LightAccumulationTexture,
                displayResolutionDependentFBOs, DEFERRED_LIGHTING_MATERIAL_URN, "texSceneOpaqueLightBuffer"));
    }

    /**
     * Part of the deferred lighting technique, this method applies lighting through screen-space
     * calculations to the previously flat-lit world rendering, stored in the lastUpdatedGBuffer.
     * <p>
     * See http://en.wikipedia.org/wiki/Deferred_shading for more information on the general subject.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Actual Node Processing

        renderQuad.render();

        PerformanceMonitor.endActivity();
    }
}
