// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Vector3f;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.cameras.SubmersibleCamera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.terasology.corerendering.rendering.dag.nodes.FinalPostProcessingNode.POST_FBO_URI;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo.FINAL_BUFFER;

/**
 * An instance of this node adds vignette onto the rendering achieved so far, stored in the gbuffer.
 * It should provide ability to use various vignette textures and tinting.
 * 1 Channeled transparency texture is used atm. Furthermore, depending if a screenshot has been requested,
 * it instructs the ScreenGrabber to save it to a file.
 * Stores the result into the displayResolutionDependentFBOs.FINAL_BUFFER, to be displayed on the screen.
 * Requirements: https://github.com/MovingBlocks/Terasology/issues/3040
 */
public class VignetteNode  extends AbstractNode implements PropertyChangeListener {
    private static final ResourceUrn VIGNETTE_MATERIAL_URN = new ResourceUrn("engine:prog.vignette");

    private RenderingConfig renderingConfig;
    private WorldProvider worldProvider;
    private WorldRenderer worldRenderer;
    private SubmersibleCamera activeCamera;

    private Material vignetteMaterial;
    private Mesh renderQuad;

    private boolean vignetteIsEnabled;

    // TODO: figure where from to set this variable
    private Vector3f tint = new Vector3f(.0f, .0f, .0f);

    private StateChange setVignetteInputTexture;

    public VignetteNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);
        worldProvider = context.get(WorldProvider.class);
        worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();
    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new EnableMaterial(VIGNETTE_MATERIAL_URN));
        setVignetteInputTexture = new SetInputTexture2D(1, "engine:vignette", VIGNETTE_MATERIAL_URN,
                "texVignette");

        DisplayResolutionDependentFbo displayResolution = context.get(DisplayResolutionDependentFbo.class);
        FBO finalBuffer = displayResolution.get(FINAL_BUFFER);
        addDesiredStateChange(new BindFbo(finalBuffer));
        addDesiredStateChange(new SetViewportToSizeOf(finalBuffer));
        addDesiredStateChange(new EnableMaterial(VIGNETTE_MATERIAL_URN));

        vignetteMaterial = getMaterial(VIGNETTE_MATERIAL_URN);

        renderingConfig = context.get(Config.class).getRendering();
        vignetteIsEnabled = renderingConfig.isVignette();
        renderingConfig.subscribe(RenderingConfig.VIGNETTE, this);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, POST_FBO_URI, ColorTexture,
                displayResolution, VIGNETTE_MATERIAL_URN, "texScene"));

        setVignetteInputTexture = new SetInputTexture2D(textureSlot++, "engine:vignette", VIGNETTE_MATERIAL_URN,
                "texVignette");

        if (vignetteIsEnabled) {
            addDesiredStateChange(setVignetteInputTexture);
        }

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    /**
     * Renders a quad, in turn filling the InitialPostProcessingNode.VIGNETTE_FBO_URI.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Common Shader Parameters

        vignetteMaterial.setFloat("swimming", activeCamera.isUnderWater() ? 1.0f : 0.0f, true);

        // Shader Parameters

        vignetteMaterial.setFloat3("inLiquidTint", worldProvider.getBlock(activeCamera.getPosition()).getTint(), true);
        vignetteMaterial.setFloat3("tint", tint);

        // Actual Node Processing

        renderQuad.render();

        PerformanceMonitor.endActivity();
    }


    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.VIGNETTE:
                vignetteIsEnabled = renderingConfig.isVignette();
                if (vignetteIsEnabled) {
                    addDesiredStateChange(setVignetteInputTexture);
                } else {
                    removeDesiredStateChange(setVignetteInputTexture);
                }
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }

        worldRenderer.requestTaskListRefresh();
    }


}
