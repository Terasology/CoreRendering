// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;

/**
 * An instance of this class generates a high pass image out of the color content of the GBUFFER and stores
 * the result into HIGH_PASS_FBO_URI, for other nodes to take advantage of it.
 */
public class HighPassNode extends ConditionDependentNode {
    public static final SimpleUri HIGH_PASS_FBO_URI = new SimpleUri("engine:fbo.highPass");
    public static final FboConfig HIGH_PASS_FBO_CONFIG = new FboConfig(HIGH_PASS_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT);
    private static final ResourceUrn HIGH_PASS_MATERIAL_URN = new ResourceUrn("engine:prog.highPass");

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 5.0f)
    private float highPassThreshold = 0.05f;

    private Material highPass;
    private Mesh renderQuad;

    public HighPassNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        RenderingConfig renderingConfig = context.get(Config.class).getRendering();
        renderingConfig.subscribe(RenderingConfig.BLOOM, this);
        requiresCondition(renderingConfig::isBloom);
        addOutputFboConnection(1);
        addOutputBufferPairConnection(1);

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
        FBO highPassFbo = requiresFbo(HIGH_PASS_FBO_CONFIG, displayResolutionDependentFBOs);
        addDesiredStateChange(new BindFbo(highPassFbo));
        addOutputFboConnection(1, highPassFbo);
        addDesiredStateChange(new SetViewportToSizeOf(highPassFbo));

        highPass = getMaterial(HIGH_PASS_MATERIAL_URN);
        addDesiredStateChange(new EnableMaterial(HIGH_PASS_MATERIAL_URN));

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        addOutputBufferPairConnection(1, bufferPairConnection);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot, lastUpdatedGBuffer, ColorTexture,
                displayResolutionDependentFBOs, HIGH_PASS_MATERIAL_URN, "tex"));
    }

    /**
     * Generates a high pass image out of the color content of the GBUFFER and stores it
     * into the HIGH_PASS_FBO_URI.
     *
     * This is an entirely 2D process and the only "3D" geometry involved is a full screen quad.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        highPass.setFloat("highPassThreshold", highPassThreshold, true);

        renderQuad.render();

        PerformanceMonitor.endActivity();
    }
}
