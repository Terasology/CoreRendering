// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Vector3f;
import org.terasology.corerendering.rendering.CoreRenderingModule;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.module.rendering.RenderingModuleRegistry;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.assets.shader.ShaderProgramFeature;
import org.terasology.engine.rendering.backdrop.BackdropProvider;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.DisableDepthTest;
import org.terasology.engine.rendering.dag.stateChanges.EnableBlending;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetBlendFunction;
import org.terasology.engine.rendering.dag.stateChanges.SetFboWriteMask;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.logic.LightComponent;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.opengl.fbms.ShadowMapResolutionDependentFbo;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.LightAccumulationTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.NormalsTexture;

// TODO: have this node and the shadowmap node handle multiple directional lights

/**
 * This class is integral to the deferred rendering process.
 * It renders the main light (sun/moon) as a directional light, a type of light emitting parallel rays as is
 * appropriate for astronomical light sources.
 *
 * This achieved by blending a single color into each pixel of the light accumulation buffer, the single
 * color being dependent only on the angle between the camera and the light direction.
 *
 * Eventually the content of the light accumulation buffer is combined with other buffers to correctly
 * light up the 3d scene.
 */
public class DeferredMainLightNode extends AbstractNode {
    private static final ResourceUrn LIGHT_GEOMETRY_MATERIAL_URN = new ResourceUrn("CoreRendering:lightGeometryPass");

    private BackdropProvider backdropProvider;
    private RenderingConfig renderingConfig;
    private WorldProvider worldProvider;
    private CoreRenderingModule coreRendering;

    private LightComponent mainLightComponent = new LightComponent();

    private Material lightGeometryMaterial;
    private Mesh renderQuad;


    private Camera activeCamera;
    private Camera lightCamera;
    @SuppressWarnings("FieldCanBeLocal")
    private Vector3f cameraPosition;
    @SuppressWarnings("FieldCanBeLocal")
    private Vector3f activeCameraToLightSpace = new Vector3f();
    @SuppressWarnings("FieldCanBeLocal")
    private Vector3f mainLightInViewSpace = new Vector3f();

    public DeferredMainLightNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        backdropProvider = context.get(BackdropProvider.class);
        renderingConfig = context.get(Config.class).getRendering();
        worldProvider = context.get(WorldProvider.class);
        coreRendering = ((CoreRenderingModule) context.get(RenderingModuleRegistry.class)
                .getModuleRenderingByClass(CoreRenderingModule.class));

        WorldRenderer worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();

        addOutputBufferPairConnection(1);
        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new EnableMaterial(LIGHT_GEOMETRY_MATERIAL_URN));
        lightGeometryMaterial = getMaterial(LIGHT_GEOMETRY_MATERIAL_URN);

        addDesiredStateChange(new DisableDepthTest());

        addDesiredStateChange(new EnableBlending());
        addDesiredStateChange(new SetBlendFunction(GL_ONE, GL_ONE_MINUS_SRC_COLOR));

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        addOutputBufferPairConnection(1, bufferPairConnection);
        // TODO: make sure to read from the lastUpdatedGBuffer and write to the staleGBuffer.
        addDesiredStateChange(new BindFbo(lastUpdatedGBuffer));
        addDesiredStateChange(new SetFboWriteMask(lastUpdatedGBuffer, false, false, true));

        initMainDirectionalLight();

        ShadowMapResolutionDependentFbo shadowMapResolutionDependentFBOs = context.get(ShadowMapResolutionDependentFbo.class);
        DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, DepthStencilTexture,
                displayResolutionDependentFBOs, LIGHT_GEOMETRY_MATERIAL_URN, "texSceneOpaqueDepth"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, NormalsTexture,
                displayResolutionDependentFBOs, LIGHT_GEOMETRY_MATERIAL_URN, "texSceneOpaqueNormals"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, LightAccumulationTexture,
                displayResolutionDependentFBOs, LIGHT_GEOMETRY_MATERIAL_URN, "texSceneOpaqueLightBuffer"));
        if (renderingConfig.isDynamicShadows()) {
            addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, getInputFboData(1), DepthStencilTexture,
                    shadowMapResolutionDependentFBOs, LIGHT_GEOMETRY_MATERIAL_URN, "texSceneShadowMap"));

            if (renderingConfig.isCloudShadows()) {
                addDesiredStateChange(new SetInputTexture2D(textureSlot, "engine:perlinNoiseTileable",
                        LIGHT_GEOMETRY_MATERIAL_URN, "texSceneClouds"));
            }
        }
    }

    // TODO: one day the main light (sun/moon) should be just another light in the scene.
    private void initMainDirectionalLight() {
        mainLightComponent.lightType = LightComponent.LightType.DIRECTIONAL;
        mainLightComponent.lightAmbientIntensity = 0.75f;
        mainLightComponent.lightDiffuseIntensity = 0.75f;
        mainLightComponent.lightSpecularPower = 100f;
    }

    /**
     * Renders the main light (sun/moon) as a uniformly colored full-screen quad.
     * This gets blended into the existing data stored in the light accumulation buffer.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        lightGeometryMaterial.activateFeature(ShaderProgramFeature.FEATURE_LIGHT_DIRECTIONAL);

        // Common Shader Parameters

        lightGeometryMaterial.setFloat("daylight", backdropProvider.getDaylight(), true);

        // Specific Shader Parameters

        cameraPosition = activeCamera.getPosition();
        mainLightInViewSpace = backdropProvider.getSunDirection(true);
        activeCamera.getViewMatrix().transformPosition(mainLightInViewSpace);

        // TODO: This is necessary right now because activateFeature removes all material parameters.
        // TODO: Remove this explicit binding once we get rid of activateFeature, or find a way to retain parameters through it.
        lightGeometryMaterial.setInt("texSceneOpaqueDepth", 0, true);
        lightGeometryMaterial.setInt("texSceneOpaqueNormals", 1, true);
        lightGeometryMaterial.setInt("texSceneOpaqueLightBuffer", 2, true);
        if (renderingConfig.isDynamicShadows()) {
            lightGeometryMaterial.setInt("texSceneShadowMap", 3, true);
            if (renderingConfig.isCloudShadows()) {
                lightGeometryMaterial.setInt("texSceneClouds", 4, true);
                lightGeometryMaterial.setFloat("time", worldProvider.getTime().getDays(), true);
                lightGeometryMaterial.setFloat3("cameraPosition", cameraPosition, true);
            }
        }

        if (renderingConfig.isDynamicShadows()) {
            lightCamera = coreRendering.getLightCamera();
            cameraPosition.sub(lightCamera.getPosition(), activeCameraToLightSpace);
            lightGeometryMaterial.setMatrix4("lightViewProjMatrix", lightCamera.getViewProjectionMatrix(), true);
            lightGeometryMaterial.setMatrix4("invViewProjMatrix", activeCamera.getInverseViewProjectionMatrix(), true);
            lightGeometryMaterial.setFloat3("activeCameraToLightSpace", activeCameraToLightSpace, true);
        }

        // Note: no need to set a camera here: the render takes place
        // with a default opengl camera and the quad is in front of it.

        lightGeometryMaterial.setFloat3("lightViewPos", mainLightInViewSpace, true);
        lightGeometryMaterial.setFloat3("lightColorDiffuse", mainLightComponent.lightColorDiffuse.x,
            mainLightComponent.lightColorDiffuse.y, mainLightComponent.lightColorDiffuse.z, true);
        lightGeometryMaterial.setFloat3("lightColorAmbient", mainLightComponent.lightColorAmbient.x,
            mainLightComponent.lightColorAmbient.y, mainLightComponent.lightColorAmbient.z, true);
        lightGeometryMaterial.setFloat3("lightProperties", mainLightComponent.lightAmbientIntensity,
            mainLightComponent.lightDiffuseIntensity, mainLightComponent.lightSpecularPower, true);

        // Actual Node Processing

        this.renderQuad.render(); // renders the light.

        lightGeometryMaterial.deactivateFeature(ShaderProgramFeature.FEATURE_LIGHT_DIRECTIONAL);

        PerformanceMonitor.endActivity();
    }
}
