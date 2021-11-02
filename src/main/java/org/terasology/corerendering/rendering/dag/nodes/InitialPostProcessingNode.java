// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.corerendering.rendering.utils.UnderwaterHelper;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;

/**
 * An instance of this node adds chromatic aberration (currently non-functional), light shafts,
 * 1/8th resolution bloom and vignette onto the rendering achieved so far, stored in the gbuffer.
 * Stores the result into the InitialPostProcessingNode.INITIAL_POST_FBO_URI, to be used at a later stage.
 */
public class InitialPostProcessingNode extends AbstractNode implements PropertyChangeListener {
    static final SimpleUri INITIAL_POST_FBO_URI = new SimpleUri("engine:fbo.initialPost");
    private static final ResourceUrn INITIAL_POST_MATERIAL_URN = new ResourceUrn("CoreRendering:initialPost");

    private RenderingConfig renderingConfig;
    private WorldProvider worldProvider;
    private WorldRenderer worldRenderer;
    private Camera activeCamera;
    private DisplayResolutionDependentFbo displayResolutionDependentFbo;

    private Material initialPostMaterial;

    private int textureSlot = 0;

    private boolean bloomIsEnabled;
    private int texBloomSlot = -1;
    private boolean lightShaftsAreEnabled;

    private StateChange setLightShaftsInputTexture;
    private StateChange setBloomInputTexture;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 1.0f)
    private float bloomFactor = 0.5f;
    private Mesh renderQuad;

    public InitialPostProcessingNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        worldProvider = context.get(WorldProvider.class);

        worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();

        renderingConfig = context.get(Config.class).getRendering();
        bloomIsEnabled = renderingConfig.isBloom();
        renderingConfig.subscribe(RenderingConfig.BLOOM, this);
        lightShaftsAreEnabled = renderingConfig.isLightShafts();
        renderingConfig.subscribe(RenderingConfig.LIGHT_SHAFTS, this);

        addOutputFboConnection(1);
        addOutputBufferPairConnection(1);

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);
        // TODO: see if we could write this straight into a GBUFFER
        FBO initialPostFbo = requiresFbo(new FboConfig(INITIAL_POST_FBO_URI, FULL_SCALE, FBO.Type.HDR), displayResolutionDependentFbo);
        addDesiredStateChange(new BindFbo(initialPostFbo));
        addOutputFboConnection(1, initialPostFbo);

        addDesiredStateChange(new SetViewportToSizeOf(initialPostFbo));

        addDesiredStateChange(new EnableMaterial(INITIAL_POST_MATERIAL_URN));

        initialPostMaterial = getMaterial(INITIAL_POST_MATERIAL_URN);

        // FBO bloomFbo = getInputFboData(1);

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        FBO lastUpdatedFbo = bufferPairConnection.getBufferPair().getPrimaryFbo();
        addOutputBufferPairConnection(1, bufferPairConnection);

        // FBO lightShaftsFbo = getInputFboData(1);

        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedFbo, ColorTexture, displayResolutionDependentFbo,
                INITIAL_POST_MATERIAL_URN, "texScene"));
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:vignette",
                INITIAL_POST_MATERIAL_URN, "texVignette"));

        if (bloomIsEnabled) {
            if (texBloomSlot < 0) {
                texBloomSlot = textureSlot++;
            }
            setBloomInputTexture = new SetInputTextureFromFbo(texBloomSlot, getInputFboData(2), ColorTexture,
                    displayResolutionDependentFbo, INITIAL_POST_MATERIAL_URN, "texBloom");
            addDesiredStateChange(setBloomInputTexture);
        }
        if (lightShaftsAreEnabled) {
            if (texBloomSlot < 0) {
                texBloomSlot = textureSlot++;
            }
            setLightShaftsInputTexture = new SetInputTextureFromFbo(texBloomSlot, getInputFboData(1), ColorTexture,
                    displayResolutionDependentFbo, INITIAL_POST_MATERIAL_URN, "texLightShafts");
            addDesiredStateChange(setLightShaftsInputTexture);
        }
    }

    /**
     * Renders a quad, in turn filling the InitialPostProcessingNode.INITIAL_POST_FBO_URI.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Common Shader Parameters

        initialPostMaterial.setFloat("swimming", UnderwaterHelper.isUnderwater(
                activeCamera.getPosition(), worldProvider, renderingConfig) ? 1.0f : 0.0f, true);

        // Shader Parameters

        initialPostMaterial.setFloat3("inLiquidTint", worldProvider.getBlock(activeCamera.getPosition()).getTint(), true);

        if (bloomIsEnabled) {
            initialPostMaterial.setFloat("bloomFactor", bloomFactor, true);
        }

        // Actual Node Processing
        this.renderQuad.render();

        PerformanceMonitor.endActivity();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.BLOOM:
                bloomIsEnabled = renderingConfig.isBloom();
                if (bloomIsEnabled) {
                    if (texBloomSlot < 0) {
                        texBloomSlot = textureSlot++;
                    }
                    setBloomInputTexture = new SetInputTextureFromFbo(texBloomSlot, getInputFboData(2), ColorTexture,
                            displayResolutionDependentFbo, INITIAL_POST_MATERIAL_URN, "texBloom");
                    addDesiredStateChange(setBloomInputTexture);
                } else {
                    removeDesiredStateChange(setBloomInputTexture);
                }
                break;

            case RenderingConfig.LIGHT_SHAFTS:
                lightShaftsAreEnabled = renderingConfig.isLightShafts();
                if (lightShaftsAreEnabled) {
                    if (texBloomSlot < 0) {
                        texBloomSlot = textureSlot++;
                    }
                    setLightShaftsInputTexture = new SetInputTextureFromFbo(texBloomSlot, getInputFboData(1), ColorTexture,
                            displayResolutionDependentFbo, INITIAL_POST_MATERIAL_URN, "texLightShafts");
                    addDesiredStateChange(setLightShaftsInputTexture);
                } else {
                    removeDesiredStateChange(setLightShaftsInputTexture);
                }
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }

        worldRenderer.requestTaskListRefresh();
    }
}
