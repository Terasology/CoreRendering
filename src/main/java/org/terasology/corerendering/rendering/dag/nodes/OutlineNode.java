// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.cameras.SubmersibleCamera;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.opengl.OpenGLUtils.renderFullscreenQuad;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;

/**
 * This nodes (or rather the shader used by it) takes advantage of the Sobel operator [1] to trace outlines (silhouette
 * edges) of objects at some distance from the player.
 * <p>
 * The resulting outlines are stored in a separate buffer the content of which is later composed over the more complete
 * rendering of the 3d scene.
 * <p>
 * [1] https://en.wikipedia.org/wiki/Sobel_operator
 */
public class OutlineNode extends ConditionDependentNode {
    public static final SimpleUri OUTLINE_FBO_URI = new SimpleUri("engine:fbo.outline");
    private static final ResourceUrn OUTLINE_MATERIAL_URN = new ResourceUrn("engine:prog.sobel");

    private final RenderingConfig renderingConfig;
    private final SubmersibleCamera activeCamera;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 16.0f)
    private final float pixelOffsetX = 1.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 16.0f)
    private final float pixelOffsetY = 1.0f;
    private Material outlineMaterial;
    private FBO lastUpdatedGBuffer;

    public OutlineNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        activeCamera = worldRenderer.getActiveCamera();

        renderingConfig = context.get(Config.class).getRendering();
        renderingConfig.subscribe(RenderingConfig.OUTLINE, this);
        requiresCondition(() -> renderingConfig.isOutline());

        addOutputFboConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();

        DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
        FBO outlineFbo = requiresFbo(new FboConfig(OUTLINE_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT),
                displayResolutionDependentFBOs);
        addOutputFboConnection(1, outlineFbo);

        addDesiredStateChange(new BindFbo(outlineFbo));

        addDesiredStateChange(new EnableMaterial(OUTLINE_MATERIAL_URN));

        outlineMaterial = getMaterial(OUTLINE_MATERIAL_URN);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot, lastUpdatedGBuffer, DepthStencilTexture,
                displayResolutionDependentFBOs, OUTLINE_MATERIAL_URN, "texDepth"));
    }

    /**
     * Enabled by the "outline" option in the render settings, this method generates landscape/objects outlines and
     * stores them into a buffer in its own FBO. The stored image is eventually combined with others.
     * <p>
     * The outlines visually separate a given object (including the landscape) or parts of it from sufficiently distant
     * objects it overlaps. It is effectively a depth-based edge detection technique and internally uses a Sobel
     * operator.
     * <p>
     * For further information see: http://en.wikipedia.org/wiki/Sobel_operator
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Shader Parameters

        outlineMaterial.setFloat3("cameraParameters", activeCamera.getzNear(), activeCamera.getzFar(), 0.0f, true);

        outlineMaterial.setFloat("texelWidth", 1.0f / lastUpdatedGBuffer.width());
        outlineMaterial.setFloat("texelHeight", 1.0f / lastUpdatedGBuffer.height());

        outlineMaterial.setFloat("pixelOffsetX", pixelOffsetX);
        outlineMaterial.setFloat("pixelOffsetY", pixelOffsetY);

        // Actual Node Processing

        renderFullscreenQuad();

        PerformanceMonitor.endActivity();
    }
}
