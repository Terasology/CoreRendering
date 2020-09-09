// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import static org.terasology.engine.rendering.opengl.OpenGLUtils.renderFullscreenQuad;

/**
 * A BlurNode takes the content of the color buffer attached to the input FBO and generates a blurred version of it in
 * the color buffer attached to the output FBO.
 */
public class BlurNode extends ConditionDependentNode {
    private static final ResourceUrn BLUR_MATERIAL_URN = new ResourceUrn("engine:prog.blur");
    private final FBO outputFbo;
    protected float blurRadius;
    private Material blurMaterial;
    private FBO inputFbo;

    /**
     * Constructs a BlurNode instance.
     *
     * @param outputFbo The output fbo, to store the blurred image.
     * @param blurRadius the blur radius: higher values cause higher blur. The shader's default is 16.0f.
     */
    public BlurNode(String nodeUri, Context context, Name providingModule, FBO outputFbo, float blurRadius) {
        super(nodeUri, providingModule, context);

        this.blurRadius = blurRadius;

        // TODO not sure this can be in here, it's its own out, so maybe this can stay
        this.outputFbo = outputFbo;
        addOutputFboConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        inputFbo = this.getInputFboData(1);
        addOutputFboConnection(1, outputFbo);
        addDesiredStateChange(new BindFbo(outputFbo));
        addDesiredStateChange(new SetViewportToSizeOf(outputFbo));

        addDesiredStateChange(new EnableMaterial(BLUR_MATERIAL_URN));
        this.blurMaterial = getMaterial(BLUR_MATERIAL_URN);
    }

    /**
     * Performs the blur.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // TODO: these shader-related operations should go in their own StateChange implementations
        blurMaterial.setFloat("radius", blurRadius, true);
        blurMaterial.setFloat2("texelSize", 1.0f / outputFbo.width(), 1.0f / outputFbo.height(), true);

        // TODO: binding the color buffer of an FBO should also be done in its own StateChange implementation
        inputFbo.bindTexture();

        renderFullscreenQuad();

        PerformanceMonitor.endActivity();
    }

}
