// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.ScreenGrabber;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;

/**
 * The exposure calculated earlier in the rendering process is used by an instance
 * of this node to remap the colors of the image rendered so far, brightening otherwise
 * undetailed dark areas or dimming otherwise burnt bright areas, depending on the circumstances.
 *
 * For more details on the specific algorithm used see shader resource toneMapping_frag.glsl.
 *
 * This node stores its output in TONE_MAPPED_FBO_URI.
 */
public class ToneMappingNode extends AbstractNode {
    public static final SimpleUri TONE_MAPPING_FBO_URI = new SimpleUri("engine:fbo.toneMapping");
    private static final ResourceUrn TONE_MAPPING_MATERIAL_URN = new ResourceUrn("CoreRendering:toneMapping");

    private ScreenGrabber screenGrabber;

    private Material toneMappingMaterial;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 10.0f)
    private float exposureBias = 1.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 100.0f)
    private float whitePoint = 9f;

    private Mesh renderQuad;

    public ToneMappingNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        screenGrabber = context.get(ScreenGrabber.class);
        addOutputFboConnection(1);
        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        DisplayResolutionDependentFbo displayResolutionDependentFboManager = context.get(DisplayResolutionDependentFbo.class);
        FBO toneMappingFbo = requiresFbo(new FboConfig(TONE_MAPPING_FBO_URI, FULL_SCALE, FBO.Type.HDR), displayResolutionDependentFboManager);

        addOutputFboConnection(1, toneMappingFbo);

        //DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
        //FBO toneMappingFbo = requiresFbo(new FboConfig(TONE_MAPPING_FBO_URI, FULL_SCALE, FBO.Type.HDR), displayResolutionDependentFBOs);
        addDesiredStateChange(new BindFbo(toneMappingFbo));
        addDesiredStateChange(new SetViewportToSizeOf(toneMappingFbo));

        addDesiredStateChange(new EnableMaterial(TONE_MAPPING_MATERIAL_URN));

        toneMappingMaterial = getMaterial(TONE_MAPPING_MATERIAL_URN);

        FBO initialPostProcessingFbo = getInputFboData(1);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot, initialPostProcessingFbo, ColorTexture,
                displayResolutionDependentFboManager, TONE_MAPPING_MATERIAL_URN, "texScene"));
    }

    /**
     * Renders a full screen quad with the opengl state defined by the initialise() method,
     * using the GBUFFER as input and filling the TONE_MAPPED_FBO_URI with the output of
     * the shader operations. As such, this method performs purely 2D operations.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Specific Shader Parameters
        toneMappingMaterial.setFloat("exposure", screenGrabber.getExposure() * exposureBias, true);
        toneMappingMaterial.setFloat("whitePoint", whitePoint, true);

        // Actual Node Processing
        this.renderQuad.render();

        PerformanceMonitor.endActivity();
    }
}
