// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.lwjgl.opengl.GL11;
import org.terasology.engine.config.Config;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.openvrprovider.OpenVRProvider;
import org.terasology.engine.rendering.world.WorldRenderer.RenderingStage;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import static org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT;
import static org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;
import static org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo.FINAL_BUFFER;

public class OutputToHMDNode extends ConditionDependentNode {
    private static final SimpleUri LEFT_EYE_FBO_URI = new SimpleUri("engine:fbo.leftEye");
    private static final SimpleUri RIGHT_EYE_FBO_URI = new SimpleUri("engine:fbo.rightEye");
    private static final ResourceUrn OUTPUT_TEXTURED_MATERIAL_URN = new ResourceUrn("engine:prog.outputPass");
    // TODO: make these configurable options

    private OpenVRProvider vrProvider;
    private Mesh renderQuad;

    private FBO leftEyeFbo;
    private FBO rightEyeFbo;
    private FBO finalFbo;

    /**
     * Constructs an instance of this node. Specifically, initialize the vrProvider and pass the frame buffer
     * information for the vrProvider to use.
     */
    public OutputToHMDNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        vrProvider = context.get(OpenVRProvider.class);
        requiresCondition(() -> (context.get(Config.class).getRendering().isVrSupport() && vrProvider.isInitialized()));

        // TODO: Consider reworking this, since it might cause problems later, when we support switching vr in-game.
        if (this.isEnabled()) {
            DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);

            leftEyeFbo = requiresFbo(new FboConfig(LEFT_EYE_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT).useDepthBuffer(), displayResolutionDependentFBOs);
            rightEyeFbo = requiresFbo(new FboConfig(RIGHT_EYE_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT).useDepthBuffer(), displayResolutionDependentFBOs);
            finalFbo = displayResolutionDependentFBOs.get(FINAL_BUFFER);
/* TODO: Re-enable when we try to get OpenVR working again. Disabled due to a natives issue with
            vrProvider.texType[0].handle = leftEyeFbo.getColorBufferTextureId();
            vrProvider.texType[0].eColorSpace = JOpenVRLibrary.EColorSpace.EColorSpace_ColorSpace_Gamma;
            vrProvider.texType[0].eType = JOpenVRLibrary.EGraphicsAPIConvention.EGraphicsAPIConvention_API_OpenGL;
            vrProvider.texType[0].write();
            vrProvider.texType[1].handle = rightEyeFbo.getColorBufferTextureId();
            vrProvider.texType[1].eColorSpace = JOpenVRLibrary.EColorSpace.EColorSpace_ColorSpace_Gamma;
            vrProvider.texType[1].eType = JOpenVRLibrary.EGraphicsAPIConvention.EGraphicsAPIConvention_API_OpenGL;
            vrProvider.texType[1].write();
*/
            addDesiredStateChange(new EnableMaterial(OUTPUT_TEXTURED_MATERIAL_URN));
        }

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {

    }

    /**
     * Actually perform the rendering-related tasks.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());
        finalFbo.bindTexture();
        renderFinalStereoImage(worldRenderer.getCurrentRenderStage());
        PerformanceMonitor.endActivity();
    }

    private void renderFinalStereoImage(RenderingStage renderingStage) {
        // TODO: verify if we can use glCopyTexSubImage2D instead of pass-through shaders,
        // TODO: in terms of code simplicity and performance.
        switch (renderingStage) {
            case LEFT_EYE:
                vrProvider.updateState();
                leftEyeFbo.bind();
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                this.renderQuad.render();
                break;

            case RIGHT_EYE:
                rightEyeFbo.bind();
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                this.renderQuad.render();
                vrProvider.submitFrame();
                GL11.glFinish();
                break;
        }

        // Bind the default FBO. The DAG does not recognize that this node has
        // bound a different FBO, so as far as it is concerned, FBO 0 is still
        // bound. As a result, without the below line, the image is only copied
        // to the HMD - not to the screen as we would like. To get around this,
        // we bind the default FBO here at the end.  This is a bit brittle
        // because it assumes that FBO 0 is bound before this node is run.
        // TODO: break this node into two different nodes that use addDesiredStateChange(BindFbo...))
        glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    }
}
