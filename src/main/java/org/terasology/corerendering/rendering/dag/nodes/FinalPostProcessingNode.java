// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Vector3f;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.input.cameraTarget.CameraTargetSystem;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.assets.texture.TextureUtil;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture3D;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.ScreenGrabber;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.utilities.random.FastRandom;
import org.terasology.engine.utilities.random.Random;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;

/**
 * An instance of this class adds depth of field blur, motion blur and film grain to the rendering
 * of the scene obtained so far. Furthermore, depending if a screenshot has been requested,
 * it instructs the ScreenGrabber to save it to a file.
 *
 * If RenderingDebugConfig.isEnabled() returns true, this node is instead responsible for displaying
 * the content of a number of technical buffers rather than the final, post-processed rendering
 * of the scene.
 */
public class FinalPostProcessingNode extends AbstractNode implements PropertyChangeListener {
    public static final SimpleUri POST_FBO_URI = new SimpleUri("engine:fbo.finalBuffer");
    private static final ResourceUrn POST_MATERIAL_URN = new ResourceUrn("CoreRendering:post");

    private WorldRenderer worldRenderer;
    private RenderingConfig renderingConfig;
    private ScreenGrabber screenGrabber;

    private Material postMaterial;

    private Random randomGenerator = new FastRandom();

    private CameraTargetSystem cameraTargetSystem;
    private Camera activeCamera;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 1.0f)
    private float filmGrainIntensity = 0.05f;

    private FBO lastUpdatedGBuffer;

    private StateChange setBlurTexture;
    private StateChange setNoiseTexture;
    private StateChange setVignetteInputTexture;
    private Mesh renderQuad;

    private Vector3f tint = new Vector3f(.0f, .0f, .0f);

    private final int noiseTextureSize = 1024;

    public FinalPostProcessingNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();
        screenGrabber = context.get(ScreenGrabber.class);
        cameraTargetSystem = context.get(CameraTargetSystem.class);

        postMaterial = getMaterial(POST_MATERIAL_URN);

        renderingConfig = context.get(Config.class).getRendering();
        renderingConfig.subscribe(RenderingConfig.FILM_GRAIN, this);
        renderingConfig.subscribe(RenderingConfig.MOTION_BLUR, this);
        renderingConfig.subscribe(RenderingConfig.BLUR_INTENSITY, this);
        addOutputFboConnection(1);
        addOutputBufferPairConnection(1);

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new EnableMaterial(POST_MATERIAL_URN));

        DisplayResolutionDependentFbo displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);
        FBO finalBuffer = displayResolutionDependentFbo.request(new FboConfig(POST_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT));
        addOutputFboConnection(1, finalBuffer);
        addDesiredStateChange(new BindFbo(finalBuffer));
        addDesiredStateChange(new SetViewportToSizeOf(finalBuffer));

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        addOutputBufferPairConnection(1, bufferPairConnection);

        int texId = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(texId++, this.getInputFboData(1), ColorTexture, displayResolutionDependentFbo, POST_MATERIAL_URN, "texScene"));
        addDesiredStateChange(new SetInputTextureFromFbo(texId++, lastUpdatedGBuffer, DepthStencilTexture, displayResolutionDependentFbo, POST_MATERIAL_URN, "texDepth"));
        setBlurTexture = new SetInputTextureFromFbo(texId++, this.getInputFboData(2), ColorTexture, displayResolutionDependentFbo, POST_MATERIAL_URN, "texBlur");
        addDesiredStateChange(new SetInputTexture3D(texId++, "engine:colorGradingLut1", POST_MATERIAL_URN, "texColorGradingLut"));

        // TODO: evaluate the possibility to use GPU-based noise algorithms instead of CPU-generated textures.
        setNoiseTexture = new SetInputTexture2D(texId, TextureUtil.getTextureUriForWhiteNoise(noiseTextureSize, 0x1234, 0, 512).toString(), POST_MATERIAL_URN, "texNoise");
        setVignetteInputTexture = new SetInputTexture2D(texId++, "engine:vignette", POST_MATERIAL_URN, "texVignette");


        if (renderingConfig.getBlurIntensity() != 0) {
            addDesiredStateChange(setBlurTexture);
        }

        if (renderingConfig.isVignette()) {
            addDesiredStateChange(setVignetteInputTexture);
        }

        if (renderingConfig.isFilmGrain()) {
            addDesiredStateChange(setNoiseTexture);
        }
    }

    /**
     * Execute the final post processing on the rendering of the scene obtained so far.
     * <p>
     * It uses the data stored in multiple FBOs as input and the FINAL FBO to store its output, rendering everything to a quad.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        postMaterial.setFloat("focalDistance", cameraTargetSystem.getFocalDistance(), true); //for use in DOF effect

        if (renderingConfig.isFilmGrain()) {
            postMaterial.setFloat("grainIntensity", filmGrainIntensity, true);
            postMaterial.setFloat("noiseOffset", randomGenerator.nextFloat(), true);

            postMaterial.setFloat2("noiseSize", noiseTextureSize, noiseTextureSize, true);
            postMaterial.setFloat2("renderTargetSize", lastUpdatedGBuffer.width(), lastUpdatedGBuffer.height(), true);
        }

        if (renderingConfig.isMotionBlur()) {
            postMaterial.setMatrix4("invViewProjMatrix", activeCamera.getInverseViewProjectionMatrix(), true);
            postMaterial.setMatrix4("prevViewProjMatrix", activeCamera.getPrevViewProjectionMatrix(), true);
        }

        if (renderingConfig.isVignette()) {
            postMaterial.setFloat3("tint", tint);
        }

        this.renderQuad.render();

        if (screenGrabber.isTakingScreenshot()) {
            screenGrabber.saveScreenshot();
        }

        PerformanceMonitor.endActivity();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();
        switch (propertyName) {
            case RenderingConfig.FILM_GRAIN:
                if (renderingConfig.isFilmGrain()) {
                    addDesiredStateChange(setNoiseTexture);
                } else {
                    removeDesiredStateChange(setNoiseTexture);
                }
                break;
            case RenderingConfig.VIGNETTE:
                if (renderingConfig.isVignette()) {
                    addDesiredStateChange(setVignetteInputTexture);
                } else {
                    removeDesiredStateChange(setVignetteInputTexture);
                }
                break;
            case RenderingConfig.BLUR_INTENSITY:
                if (renderingConfig.getBlurIntensity() != 0) {
                    addDesiredStateChange(setBlurTexture);
                } else {
                    removeDesiredStateChange(setBlurTexture);
                }
                break;
        }
        worldRenderer.requestTaskListRefresh();
    }
}
