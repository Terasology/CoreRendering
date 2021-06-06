/*
 * Copyright 2020 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.terasology.corerendering.rendering.CoreRenderingModule;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.module.rendering.RenderingModuleRegistry;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.logic.location.LocationComponent;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.assets.mesh.SphereBuilder;
import org.terasology.engine.rendering.assets.shader.ShaderProgramFeature;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.cameras.SubmersibleCamera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.DisableDepthTest;
import org.terasology.engine.rendering.dag.stateChanges.EnableBlending;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetBlendFunction;
import org.terasology.engine.rendering.dag.stateChanges.SetFacesToCull;
import org.terasology.engine.rendering.dag.stateChanges.SetFboWriteMask;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.logic.LightComponent;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.world.WorldProvider;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.NormalsTexture;

/**
 * Instances of this class are integral to the deferred rendering process.
 * They render point lights as spheres, into the light accumulation buffer
 * (the spheres have a radius proportional to each light's attenuation radius).
 * Data from the light accumulation buffer is eventually combined with the
 * content of other buffers to correctly light up the scene.
 */
public class DeferredPointLightsNode extends AbstractNode {
    private static final ResourceUrn LIGHT_GEOMETRY_MATERIAL_URN = new ResourceUrn("engine:prog.lightGeometryPass");

    private EntityManager entityManager;
    private RenderingConfig renderingConfig;
    private WorldProvider worldProvider;

    private Material lightGeometryMaterial;

    private SubmersibleCamera activeCamera;
    private Camera lightCamera;

    @SuppressWarnings("FieldCanBeLocal")
    private Vector3f cameraPosition;
    @SuppressWarnings("FieldCanBeLocal")
    private Vector3f activeCameraToLightSpace = new Vector3f();
    private final Mesh unitSphereMesh;

    public DeferredPointLightsNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        renderingConfig = context.get(Config.class).getRendering();
        worldProvider = context.get(WorldProvider.class);
        entityManager = context.get(EntityManager.class);
        unitSphereMesh = Assets.generateAsset(
                new SphereBuilder().
                        setRadius(1.0f).
                        setHorizontalCuts(8).
                        setVerticalCuts(8).build(), Mesh.class);

        addOutputFboConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        WorldRenderer worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();
        lightCamera = ((CoreRenderingModule) context.get(RenderingModuleRegistry.class)
                        .getModuleRenderingByClass(CoreRenderingModule.class)).getLightCamera();

        lightGeometryMaterial = getMaterial(LIGHT_GEOMETRY_MATERIAL_URN);
        addDesiredStateChange(new EnableMaterial(LIGHT_GEOMETRY_MATERIAL_URN));

        addDesiredStateChange(new EnableFaceCulling());
        addDesiredStateChange(new SetFacesToCull(GL_FRONT));

        addDesiredStateChange(new EnableBlending());
        addDesiredStateChange(new SetBlendFunction(GL_ONE, GL_ONE_MINUS_SRC_COLOR));

        addDesiredStateChange(new DisableDepthTest());

        BufferPairConnection bufferPairConnection =  getInputBufferPairConnection(1);
        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        // TODO: make sure to read from the lastUpdatedGBuffer and write to the staleGBuffer.
        addDesiredStateChange(new BindFbo(lastUpdatedGBuffer));
        addOutputFboConnection(1, lastUpdatedGBuffer);
        addDesiredStateChange(new SetFboWriteMask(lastUpdatedGBuffer, false, false, true));

        DisplayResolutionDependentFbo displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);
        int textureSlot = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot++, lastUpdatedGBuffer, DepthStencilTexture, displayResolutionDependentFbo, LIGHT_GEOMETRY_MATERIAL_URN, "texSceneOpaqueDepth"));
        addDesiredStateChange(new SetInputTextureFromFbo(textureSlot, lastUpdatedGBuffer, NormalsTexture, displayResolutionDependentFbo, LIGHT_GEOMETRY_MATERIAL_URN, "texSceneOpaqueNormals"));
    }

    private boolean lightIsRenderable(LightComponent lightComponent, Vector3f lightPositionRelativeToCamera) {
        // if lightRenderingDistance is 0.0, the light is always considered, no matter the distance.
        boolean lightIsRenderable = lightComponent.lightRenderingDistance == 0.0f
                || lightPositionRelativeToCamera.lengthSquared() < (lightComponent.lightRenderingDistance * lightComponent.lightRenderingDistance);
        // above: rendering distance must be higher than distance from the camera or the light is ignored

        // No matter what, we ignore lights that are not in the camera frustrum
        lightIsRenderable &= activeCamera.getViewFrustum().testSphere(lightPositionRelativeToCamera, lightComponent.lightAttenuationRange);
        // TODO: (above) what about lights just off-frame? They might light up in-frame surfaces.

        return lightIsRenderable;
    }

    /**
     * Iterates over all available point lights and renders them as spheres into the light accumulation buffer.
     *
     * Furthermore, lights that are further from the camera than their set rendering distance are ignored,
     * while lights with a rendering distance set to 0.0 are always considered. However, only lights within
     * the camera's field of view (frustrum) are rendered.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        lightGeometryMaterial.activateFeature(ShaderProgramFeature.FEATURE_LIGHT_POINT);

        // Specific Shader Parameters

        cameraPosition = activeCamera.getPosition();

        // TODO: This is necessary right now because activateFeature removes all material parameters.
        // TODO: Remove this explicit binding once we get rid of activateFeature, or find a way to retain parameters through it.
        lightGeometryMaterial.setInt("texSceneOpaqueDepth", 0, true);
        lightGeometryMaterial.setInt("texSceneOpaqueNormals", 1, true);
        lightGeometryMaterial.setMatrix4("viewProjMatrix", activeCamera.getViewProjectionMatrix());

        if (renderingConfig.isDynamicShadows()) {
            if (renderingConfig.isCloudShadows()) {
                lightGeometryMaterial.setFloat("time", worldProvider.getTime().getDays(), true);
                lightGeometryMaterial.setFloat3("cameraPosition", cameraPosition, true);
            }
        }

        if (renderingConfig.isDynamicShadows()) {

            lightGeometryMaterial.setMatrix4("lightMatrix", new Matrix4f(
                    0.5f,0.0f,0.0f,0.0f,
                    0.0f,0.5f,0.0f,0.0f,
                    0.0f,0.0f,0.5f,0.0f,
                    0.5f,0.5f,0.5f,1.0f)
                    .mul(lightCamera.getProjectionMatrix())
                    .mul(lightCamera.getViewMatrix())
            );

            lightGeometryMaterial.setMatrix4("lightProjMatrix", lightCamera.getViewProjectionMatrix(), true);
            lightGeometryMaterial.setMatrix4("invViewProjMatrix", activeCamera.getInverseViewProjectionMatrix(), true);
            lightGeometryMaterial.setMatrix4("inverseViewMatrix", new Matrix4f(activeCamera.getViewMatrix()).invert());

            lightGeometryMaterial.setMatrix4("invViewMatrix", new Matrix4f(activeCamera.getViewMatrix()).invert(), true);
            lightGeometryMaterial.setMatrix4("invProjectionMatrix", new Matrix4f(activeCamera.getProjectionMatrix()).invert());


            cameraPosition.sub(lightCamera.getPosition(), activeCameraToLightSpace);
            lightGeometryMaterial.setFloat3("activeCameraToLightSpace", activeCameraToLightSpace.x, activeCameraToLightSpace.y, activeCameraToLightSpace.z, true);
        }

        // Actual Node Processing

        for (EntityRef entity : entityManager.getEntitiesWith(LightComponent.class, LocationComponent.class)) {
            LightComponent lightComponent = entity.getComponent(LightComponent.class);

            if (lightComponent.lightType == LightComponent.LightType.POINT) {
                LocationComponent locationComponent = entity.getComponent(LocationComponent.class);
                final Vector3f lightPositionInTeraCoords = locationComponent.getWorldPosition(new Vector3f());

                Vector3f lightPositionRelativeToCamera = new Vector3f();
                lightPositionInTeraCoords.sub(activeCamera.getPosition(),lightPositionRelativeToCamera);

                if (lightIsRenderable(lightComponent, lightPositionRelativeToCamera)) {
                    lightGeometryMaterial.setCamera(activeCamera);

                    // setting shader parameters regarding the light's properties
                    lightGeometryMaterial.setFloat3("lightColorDiffuse", lightComponent.lightColorDiffuse.x,
                        lightComponent.lightColorDiffuse.y, lightComponent.lightColorDiffuse.z, true);
                    lightGeometryMaterial.setFloat3("lightColorAmbient", lightComponent.lightColorAmbient.x,
                        lightComponent.lightColorAmbient.y, lightComponent.lightColorAmbient.z, true);
                    lightGeometryMaterial.setFloat3("lightProperties", lightComponent.lightAmbientIntensity,
                        lightComponent.lightDiffuseIntensity, lightComponent.lightSpecularPower, true);
                    lightGeometryMaterial.setFloat4("lightExtendedProperties", lightComponent.lightAttenuationRange,
                        lightComponent.lightAttenuationFalloff, 0.0f, 0.0f, true);

                    // setting shader parameters for the light position in camera space
                    Vector3f lightPositionInViewSpace = new Vector3f(lightPositionRelativeToCamera).mulPosition(activeCamera.getViewMatrix());

                    lightGeometryMaterial.setFloat3("lightViewPos", lightPositionInViewSpace.x, lightPositionInViewSpace.y, lightPositionInViewSpace.z, true);

                    // set the size and location of the sphere to be rendered via shader parameters
                    Matrix4f modelMatrix = new Matrix4f();
                    modelMatrix.scale(lightComponent.lightAttenuationRange); // scales the modelview matrix, effectively scales the light sphere
                    modelMatrix.setTranslation(lightPositionRelativeToCamera); // effectively moves the light sphere in the right position relative to camera
                    lightGeometryMaterial.setMatrix4("modelMatrix", modelMatrix, true);

                    unitSphereMesh.render();
                }
            }
        }

        lightGeometryMaterial.deactivateFeature(ShaderProgramFeature.FEATURE_LIGHT_POINT);

        PerformanceMonitor.endActivity();
    }
}
