// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.shader.ShaderProgramFeature;
import org.terasology.engine.rendering.backdrop.BackdropProvider;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.ReflectedCamera;
import org.terasology.engine.rendering.dag.stateChanges.SetFacesToCull;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.world.RenderQueuesHelper;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.chunks.RenderableChunk;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import java.beans.PropertyChangeEvent;

import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.terasology.engine.rendering.primitives.ChunkMesh.RenderPhase.OPAQUE;

/**
 * An instance of this class is responsible for rendering a reflected landscape into the
 * "engine:sceneReflected" buffer. This buffer is then used to produce the reflection
 * of the landscape on the water surface.
 *
 * It could potentially be used also for other reflecting surfaces, i.e. metal, but it only works
 * for horizontal surfaces.
 *
 * An instance of this class is enabled or disabled depending on the reflections setting in the rendering config.
 *
 * Diagram of this node can be viewed from:
 * TODO: move diagram to the wiki when this part of the code is stable
 * - https://docs.google.com/drawings/d/1Iz7MA8Y5q7yjxxcgZW-0antv5kgx6NYkvoInielbwGU/edit?usp=sharing
 */
public class WorldReflectionNode extends ConditionDependentNode {
    private static final ResourceUrn CHUNK_MATERIAL_URN = new ResourceUrn("CoreRendering:chunk");

    private RenderQueuesHelper renderQueues;
    private BackdropProvider backdropProvider;
    private WorldProvider worldProvider;

    private Material chunkMaterial;
    private RenderingConfig renderingConfig;

    private Camera activeCamera;

    private boolean isNormalMapping;
    private boolean isParallaxMapping;

    private StateChange setNormalTerrain;
    private StateChange setHeightTerrain;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 0.5f)
    private float parallaxBias = 0.25f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 0.50f)
    private float parallaxScale = 0.5f;

    /**
     * Constructs an instance of this class.
     *
     * Internally requires the "engine:sceneReflected" buffer, stored in the (display) resolution-dependent FBO manager.
     * This is a default, half-scale buffer inclusive of a depth buffer FBO. See FboConfig and ScalingFactors for details
     * on possible FBO configurations.
     *
     * This method also requests the material using the "chunk" shaders (vertex, fragment) to be enabled.
     */
    public WorldReflectionNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        renderQueues = context.get(RenderQueuesHelper.class);
        backdropProvider = context.get(BackdropProvider.class);
        worldProvider = context.get(WorldProvider.class);

        activeCamera = worldRenderer.getActiveCamera();

        addOutputFboConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new ReflectedCamera(activeCamera)); // this has to go before the LookThrough state change

        FBO reflectedFbo = getInputFboData(1);
        addOutputFboConnection(1, reflectedFbo);
        addDesiredStateChange(new BindFbo(reflectedFbo));
        addDesiredStateChange(new SetViewportToSizeOf(reflectedFbo));
        addDesiredStateChange(new EnableFaceCulling());
        addDesiredStateChange(new SetFacesToCull(GL_FRONT));
        addDesiredStateChange(new EnableMaterial(CHUNK_MATERIAL_URN));

        // TODO: improve EnableMaterial to take advantage of shader feature bitmasks.
        chunkMaterial = getMaterial(CHUNK_MATERIAL_URN);

        renderingConfig = context.get(Config.class).getRendering();
        requiresCondition(() -> renderingConfig.isReflectiveWater());
        renderingConfig.subscribe(RenderingConfig.REFLECTIVE_WATER, this);
        isNormalMapping = renderingConfig.isNormalMapping();
        renderingConfig.subscribe(RenderingConfig.NORMAL_MAPPING, this);
        isParallaxMapping = renderingConfig.isParallaxMapping();
        renderingConfig.subscribe(RenderingConfig.PARALLAX_MAPPING, this);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:terrain", CHUNK_MATERIAL_URN, "textureAtlas"));
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:effects", CHUNK_MATERIAL_URN, "textureEffects"));
        setNormalTerrain = new SetInputTexture2D(textureSlot++, "engine:terrainNormal", CHUNK_MATERIAL_URN, "textureAtlasNormal");
        setHeightTerrain = new SetInputTexture2D(textureSlot, "engine:terrainHeight", CHUNK_MATERIAL_URN, "textureAtlasHeight");

        if (isNormalMapping) {
            addDesiredStateChange(setNormalTerrain);

            if (isParallaxMapping) {
                addDesiredStateChange(setHeightTerrain);
            }
        }

    }

    /**
     * Renders the landscape, reflected, into the buffers attached to the "engine:sceneReflected" FBO. It is used later,
     * to render horizontal reflective surfaces, i.e. water.
     *
     * Notice that this method -does not- clear the FBO. The rendering takes advantage of the depth buffer to decide
     * which pixel is in front of the one already stored in the buffer.
     *
     * See: https://en.wikipedia.org/wiki/Deep_image_compositing
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        chunkMaterial.activateFeature(ShaderProgramFeature.FEATURE_USE_FORWARD_LIGHTING);

        // Common Shader Parameters

        chunkMaterial.setFloat("daylight", backdropProvider.getDaylight(), true);
        chunkMaterial.setFloat("time", worldProvider.getTime().getDays(), true);

        // Specific Shader Parameters

        // TODO: This is necessary right now because activateFeature removes all material parameters.
        // TODO: Remove this explicit binding once we get rid of activateFeature, or find a way to retain parameters through it.
        chunkMaterial.setInt("textureAtlas", 0, true);
        chunkMaterial.setInt("textureEffects", 1, true);
        if (isNormalMapping) {
            chunkMaterial.setInt("textureAtlasNormal", 2, true);
        }
        if (isParallaxMapping) {
            chunkMaterial.setInt("textureAtlasHeight", 3, true);
            chunkMaterial.setFloat4("parallaxProperties", parallaxBias, parallaxScale, 0.0f, 0.0f, true);
        }

        chunkMaterial.setFloat("clip", activeCamera.getReflectionHeight(), true);

        // Actual Node Processing

        int numberOfRenderedTriangles = 0;
        int numberOfChunksThatAreNotReadyYet = 0;

        final Vector3f cameraPosition = activeCamera.getPosition();

        Matrix4f modelViewMatrix = new Matrix4f();
        Matrix4f model = new Matrix4f();
        Matrix3f normalMatrix = new Matrix3f();
        chunkMaterial.setMatrix4("projectionMatrix", activeCamera.getProjectionMatrix(), true);

        while (renderQueues.chunksOpaqueReflection.size() > 0) {
            RenderableChunk chunk = renderQueues.chunksOpaqueReflection.poll();

            if (chunk.hasMesh()) {
                final ChunkMesh chunkMesh = chunk.getMesh();
                final Vector3f chunkPosition = chunk.getRenderPosition();

                chunkMesh.updateMaterial(chunkMaterial, chunkPosition, chunk.isAnimated());

                model.setTranslation(chunkPosition.x() - cameraPosition.x(),
                        chunkPosition.y() - cameraPosition.y(),
                        chunkPosition.z() - cameraPosition.z());
                modelViewMatrix.set(activeCamera.getViewMatrix()).mul(model);
                chunkMaterial.setMatrix4("modelViewMatrix", modelViewMatrix, true);
                chunkMaterial.setMatrix3("normalMatrix", modelViewMatrix.normal(normalMatrix), true);
                numberOfRenderedTriangles += chunkMesh.render(OPAQUE);

            } else {
                numberOfChunksThatAreNotReadyYet++;
            }
        }

        chunkMaterial.deactivateFeature(ShaderProgramFeature.FEATURE_USE_FORWARD_LIGHTING);

        worldRenderer.increaseTrianglesCount(numberOfRenderedTriangles);
        worldRenderer.increaseNotReadyChunkCount(numberOfChunksThatAreNotReadyYet);

        PerformanceMonitor.endActivity();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.REFLECTIVE_WATER:
                break;

            case RenderingConfig.NORMAL_MAPPING:
                isNormalMapping = renderingConfig.isNormalMapping();
                if (isNormalMapping) {
                    addDesiredStateChange(setNormalTerrain);
                } else {
                    removeDesiredStateChange(setNormalTerrain);
                }
                break;

            case RenderingConfig.PARALLAX_MAPPING:
                isParallaxMapping = renderingConfig.isParallaxMapping();
                if (isParallaxMapping) {
                    addDesiredStateChange(setHeightTerrain);
                } else {
                    removeDesiredStateChange(setHeightTerrain);
                }
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }

        super.propertyChange(event);
    }
}
