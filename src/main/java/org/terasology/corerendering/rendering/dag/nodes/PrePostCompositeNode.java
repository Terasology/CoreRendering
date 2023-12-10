// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.dependencyConnections.DependencyConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.LightAccumulationTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.NormalsTexture;

/**
 * An instance of this class takes advantage of the content of a number of previously filled buffers
 * to add screen-space ambient occlusion (SSAO), outlines, reflections [1], atmospheric haze and volumetric fog
 *
 * As this node does not quite use 3D geometry and only relies on 2D sources and a 2D output buffer, it
 * could be argued that, despite its name, it represents the first step of the PostProcessing portion
 * of the rendering engine. This line of thinking draws a parallel from the film industry where
 * Post-Processing (or Post-Production) is everything that happens -after- the footage for the film
 * has been shot on stage or on location.
 *
 * [1] And refractions? To be verified.
 */
public class PrePostCompositeNode extends AbstractNode implements PropertyChangeListener {
    private static final ResourceUrn PRE_POST_MATERIAL_URN = new ResourceUrn("CoreRendering:prePostComposite");

    private RenderingConfig renderingConfig;
    private WorldRenderer worldRenderer;
    private Camera activeCamera;
    private DisplayResolutionDependentFbo displayResolutionDependentFbo;

    private Material prePostMaterial;

    private int textureSlot = 0;

    private boolean localReflectionsAreEnabled;

    private boolean ssaoIsEnabled;
    private int texSsaoSlot = -1;

    private boolean outlineIsEnabled;

    private boolean hazeIsEnabled;
    private int texHazeSlot = -1;

    private boolean volumetricFogIsEnabled;

    private StateChange setReflectiveRefractiveNormalsInputTexture;
    private StateChange setSsaoInputTexture;
    private StateChange setEdgesInputTexture;
    private StateChange setHazeInputTexture;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.001f, max = 0.005f)
    private float outlineDepthThreshold = 0.001f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 1.0f)
    private float outlineThickness = 0.65f;

    // TODO : Consider a more descriptive name for this variable.
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 1.0f)
    private float hazeLength = 1.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 1.0f)
    private float hazeStrength = 0.25f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 1.0f)
    private float hazeThreshold = 0.8f;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 0.1f)
    private float volumetricFogGlobalDensity = 0.005f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = -0.1f, max = 0.1f)
    private float volumetricFogHeightFalloff = -0.01f;

    private Mesh renderQuad;

    public PrePostCompositeNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();
        addOutputBufferPairConnection(1);

        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));

    }

    @Override
    public void setDependencies(Context context) {
        // TODO: Move everything you can into constructor

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        // Add new instance of swapped bufferPair as output
        addOutputBufferPairConnection(1, bufferPairConnection.getSwappedCopy(DependencyConnection.Type.OUTPUT, this.getUri()));

        addDesiredStateChange(new EnableMaterial(PRE_POST_MATERIAL_URN));
        addDesiredStateChange(new BindFbo(bufferPairConnection.getBufferPair().getSecondaryFbo()));

        prePostMaterial = getMaterial(PRE_POST_MATERIAL_URN);

        renderingConfig = context.get(Config.class).getRendering();
        localReflectionsAreEnabled = renderingConfig.isLocalReflections();
        renderingConfig.subscribe(RenderingConfig.LOCAL_REFLECTIONS, this);
        ssaoIsEnabled = renderingConfig.isSsao();
        renderingConfig.subscribe(RenderingConfig.SSAO, this);
        outlineIsEnabled = renderingConfig.isOutline();
        renderingConfig.subscribe(RenderingConfig.OUTLINE, this);
        hazeIsEnabled = renderingConfig.isInscattering();
        renderingConfig.subscribe(RenderingConfig.INSCATTERING, this);
        volumetricFogIsEnabled = renderingConfig.isVolumetricFog();
        renderingConfig.subscribe(RenderingConfig.VOLUMETRIC_FOG, this);

        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();

        displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);
        textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer,
                ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSceneOpaque"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer,
                DepthStencilTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN,
                "texSceneOpaqueDepth"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer,
                NormalsTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSceneOpaqueNormals"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer,
                LightAccumulationTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN,
                "texSceneOpaqueLightBuffer"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, getInputFboData(4),
                ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN,
                "texSceneReflectiveRefractive"));
        setReflectiveRefractiveNormalsInputTexture = new SetInputTextureFromFbo(textureSlot++, getInputFboData(4),
                NormalsTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN,
                "texSceneReflectiveRefractiveNormals");

        setEdgesInputTexture = new SetInputTextureFromFbo(textureSlot++, getInputFboData(2),
                ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texEdges");


        if (localReflectionsAreEnabled) {
            // setReflectiveRefractiveNormalsInputTexture = new SetInputTextureFromFbo(textureSlot++, getInputFboData(4),
            // NormalsTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSceneReflectiveRefractiveNormals");
            addDesiredStateChange(setReflectiveRefractiveNormalsInputTexture);
        }
        if (ssaoIsEnabled) {
            if (texSsaoSlot < 0) {
                texSsaoSlot = textureSlot++;
            }
            setSsaoInputTexture = new SetInputTextureFromFbo(texSsaoSlot, getInputFboData(1),
                    ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSsao");
            addDesiredStateChange(setSsaoInputTexture);
        }
        if (outlineIsEnabled) {
            addDesiredStateChange(setEdgesInputTexture);
        }
        if (hazeIsEnabled) {
            if (texHazeSlot < 0) {
                texHazeSlot = textureSlot++;
            }
            setHazeInputTexture = new SetInputTextureFromFbo(texHazeSlot, getInputFboData(3),
                    ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSceneSkyBand");
            addDesiredStateChange(setHazeInputTexture);
        }
    }

    /**
     * Called every frame, the shader program used by this method only composites per-pixel information from a number
     * of buffers and renders it into a full-screen quad, which is the only piece of geometry processed.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Shader Parameters

        prePostMaterial.setFloat("viewingDistance", renderingConfig.getViewDistance().getChunkDistance().x() * 8.0f, true);
        prePostMaterial.setFloat3("cameraParameters", activeCamera.getzNear(), activeCamera.getzFar(), 0.0f, true);

        if (localReflectionsAreEnabled) {
            prePostMaterial.setMatrix4("invProjMatrix", activeCamera.getInverseProjectionMatrix(), true);
            prePostMaterial.setMatrix4("projMatrix", activeCamera.getProjectionMatrix(), true);
        }

        if (outlineIsEnabled) {
            prePostMaterial.setFloat("outlineDepthThreshold", outlineDepthThreshold, true);
            prePostMaterial.setFloat("outlineThickness", outlineThickness, true);
        }

        if (volumetricFogIsEnabled) {
            prePostMaterial.setMatrix4("invViewProjMatrix", activeCamera.getInverseViewProjectionMatrix(), true);
            prePostMaterial.setFloat3("volumetricFogSettings", 1f, volumetricFogGlobalDensity, volumetricFogHeightFalloff,
                    true);
        }

        if (hazeIsEnabled) {
            prePostMaterial.setFloat4("skyInscatteringSettingsFrag", 0, hazeStrength, hazeLength, hazeThreshold, true);
        }

        // TODO: We never set the "fogWorldPosition" uniform in prePostComposite_frag.glsl . Either use it, or remove it.

        // Actual Node Processing

        renderQuad.render();

        PerformanceMonitor.endActivity();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.LOCAL_REFLECTIONS:
                localReflectionsAreEnabled = renderingConfig.isLocalReflections();
                if (localReflectionsAreEnabled) {
                    addDesiredStateChange(setReflectiveRefractiveNormalsInputTexture);
                } else {
                    removeDesiredStateChange(setReflectiveRefractiveNormalsInputTexture);
                }
                break;

            case RenderingConfig.SSAO:
                ssaoIsEnabled = renderingConfig.isSsao();
                if (ssaoIsEnabled) {
                    if (texSsaoSlot < 0) {
                        texSsaoSlot = textureSlot++;
                    }
                    setSsaoInputTexture = new SetInputTextureFromFbo(texSsaoSlot, getInputFboData(1),
                            ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSsao");
                    addDesiredStateChange(setSsaoInputTexture);
                } else {
                    removeDesiredStateChange(setSsaoInputTexture);
                }
                break;

            case RenderingConfig.OUTLINE:
                outlineIsEnabled = renderingConfig.isOutline();
                if (outlineIsEnabled) {
                    getInputFboData(2);
                    addDesiredStateChange(setEdgesInputTexture);
                } else {
                    removeDesiredStateChange(setEdgesInputTexture);
                }
                break;

            case RenderingConfig.INSCATTERING:
                hazeIsEnabled = renderingConfig.isInscattering();
                if (hazeIsEnabled) {
                    if (texHazeSlot < 0) {
                        texHazeSlot = textureSlot++;
                    }
                    setHazeInputTexture = new SetInputTextureFromFbo(texHazeSlot, getInputFboData(3),
                            ColorTexture, displayResolutionDependentFbo, PRE_POST_MATERIAL_URN, "texSceneSkyBand");
                    addDesiredStateChange(setHazeInputTexture);
                } else {
                    removeDesiredStateChange(setHazeInputTexture);
                }
                break;

            case RenderingConfig.VOLUMETRIC_FOG:
                volumetricFogIsEnabled = renderingConfig.isVolumetricFog();
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }

        worldRenderer.requestTaskListRefresh();
    }
}
