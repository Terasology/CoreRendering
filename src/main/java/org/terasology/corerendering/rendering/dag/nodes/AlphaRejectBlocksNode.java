// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.config.RenderingDebugConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.shader.ShaderProgramFeature;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.WireframeCapable;
import org.terasology.engine.rendering.dag.WireframeTrigger;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetWireframe;
import org.terasology.engine.rendering.primitives.ChunkMesh;
import org.terasology.engine.rendering.world.RenderQueuesHelper;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.world.WorldProvider;
import org.terasology.engine.world.chunks.RenderableChunk;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.terasology.engine.rendering.primitives.ChunkMesh.RenderPhase.ALPHA_REJECT;

/**
 * This node uses alpha-rejection to render semi-transparent blocks (i.e. tree foliage) and
 * semi-transparent billboards (i.e. plants on the ground).
 *
 * Alpha-rejection is the idea that if a fragment has an alpha value lower than some threshold
 * it gets discarded, leaving the color already stored in the frame buffer untouched.
 *
 * This is a less expensive way to render semi-transparent objects compared to alpha-blending.
 * In alpha-blending the color of a semi-transparent fragment is combined with
 * the color stored in the frame buffer and the resulting color overwrites the previously stored one.
 */
public class AlphaRejectBlocksNode extends AbstractNode implements WireframeCapable, PropertyChangeListener {
    private static final ResourceUrn CHUNK_MATERIAL_URN = new ResourceUrn("CoreRendering:chunk");

    private WorldRenderer worldRenderer;
    private RenderQueuesHelper renderQueues;
    private RenderingConfig renderingConfig;
    private WorldProvider worldProvider;

    private Material chunkMaterial;
    private SetWireframe wireframeStateChange;

    private Camera activeCamera;

    private boolean normalMappingIsEnabled;
    private boolean parallaxMappingIsEnabled;

    private StateChange setTerrainNormalsInputTexture;
    private StateChange setTerrainHeightInputTexture;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 0.5f)
    private float parallaxBias = 0.25f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 0.50f)
    private float parallaxScale = 0.5f;

    public AlphaRejectBlocksNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        renderQueues = context.get(RenderQueuesHelper.class);
        worldProvider = context.get(WorldProvider.class);
        addOutputBufferPairConnection(1);
        worldRenderer = context.get(WorldRenderer.class);
    }

    @Override
    public void setDependencies(Context context) {
        activeCamera = worldRenderer.getActiveCamera();

        wireframeStateChange = new SetWireframe(true);
        RenderingDebugConfig renderingDebugConfig =  context.get(Config.class).getRendering().getDebug();
        new WireframeTrigger(renderingDebugConfig, this);

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        addOutputBufferPairConnection(1, bufferPairConnection);
        addDesiredStateChange(new BindFbo(bufferPairConnection.getBufferPair().getPrimaryFbo()));

        addDesiredStateChange(new EnableMaterial(CHUNK_MATERIAL_URN));

        chunkMaterial = getMaterial(CHUNK_MATERIAL_URN);

        renderingConfig = context.get(Config.class).getRendering();
        normalMappingIsEnabled = renderingConfig.isNormalMapping();
        renderingConfig.subscribe(RenderingConfig.NORMAL_MAPPING, this);
        parallaxMappingIsEnabled = renderingConfig.isParallaxMapping();
        renderingConfig.subscribe(RenderingConfig.PARALLAX_MAPPING, this);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:terrain", CHUNK_MATERIAL_URN, "textureAtlas"));
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:effects", CHUNK_MATERIAL_URN, "textureEffects"));
        setTerrainNormalsInputTexture = new SetInputTexture2D(textureSlot++, "engine:terrainNormal", CHUNK_MATERIAL_URN, "textureAtlasNormal");
        setTerrainHeightInputTexture = new SetInputTexture2D(textureSlot, "engine:terrainHeight", CHUNK_MATERIAL_URN, "textureAtlasHeight");

        if (normalMappingIsEnabled) {
            addDesiredStateChange(setTerrainNormalsInputTexture);
        }

        if (parallaxMappingIsEnabled) {
            addDesiredStateChange(setTerrainHeightInputTexture);
        }
    }

    public void enableWireframe() {
        if (!getDesiredStateChanges().contains(wireframeStateChange)) {
            addDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    public void disableWireframe() {
        if (getDesiredStateChanges().contains(wireframeStateChange)) {
            removeDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    /**
     * Renders the world's semi-transparent blocks, i.e. tree foliage and terrain plants.
     * Does not render fully opaque blocks, i.e. the typical landscape blocks.
     *
     * Takes advantage of the two methods
     *
     * - WorldRenderer.increaseTrianglesCount(int)
     * - WorldRenderer.increaseNotReadyChunkCount(int)
     *
     * to publish some statistics over its own activity.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        chunkMaterial.activateFeature(ShaderProgramFeature.FEATURE_ALPHA_REJECT);

        // Common Shader Parameters

        chunkMaterial.setFloat("time", worldProvider.getTime().getDays(), true);

        // Specific Shader Parameters

        // TODO: This is necessary right now because activateFeature removes all material parameters.
        // TODO: Remove this explicit binding once we get rid of activateFeature, or find a way to retain parameters through it.
        chunkMaterial.setInt("textureAtlas", 0, true);
        chunkMaterial.setInt("textureEffects", 1, true);
        if (normalMappingIsEnabled) {
            chunkMaterial.setInt("textureAtlasNormal", 2, true);
        }
        if (parallaxMappingIsEnabled) {
            chunkMaterial.setInt("textureAtlasHeight", 3, true);
            chunkMaterial.setFloat4("parallaxProperties", parallaxBias, parallaxScale, 0.0f, 0.0f, true);
        }

        chunkMaterial.setFloat("clip", 0.0f, true);

        // Actual Node Processing

        final org.joml.Vector3f cameraPosition = activeCamera.getPosition();

        int numberOfRenderedTriangles = 0;
        int numberOfChunksThatAreNotReadyYet = 0;

        Matrix4f modelViewMatrix = new Matrix4f();
        Matrix4f model = new Matrix4f();
        Matrix3f normalMatrix = new Matrix3f();
        chunkMaterial.setMatrix4("projectionMatrix", activeCamera.getProjectionMatrix(), true);

        while (renderQueues.chunksAlphaReject.size() > 0) {
            RenderableChunk chunk = renderQueues.chunksAlphaReject.poll();

            if (chunk.hasMesh()) {
                final ChunkMesh chunkMesh = chunk.getMesh();
                final Vector3fc chunkPosition = chunk.getRenderPosition();

                chunkMesh.updateMaterial(chunkMaterial, chunkPosition, chunk.isAnimated());

                model.setTranslation(chunkPosition.x() - cameraPosition.x(),
                        chunkPosition.y() - cameraPosition.y(),
                        chunkPosition.z() - cameraPosition.z());
                modelViewMatrix.set(activeCamera.getViewMatrix()).mul(model);

                chunkMaterial.setMatrix4("modelViewMatrix", modelViewMatrix, true);
                chunkMaterial.setMatrix3("normalMatrix", modelViewMatrix.normal(normalMatrix), true);
                numberOfRenderedTriangles += chunkMesh.render(ALPHA_REJECT);

            } else {
                numberOfChunksThatAreNotReadyYet++; // TODO: verify - should we count them only in ChunksOpaqueNode?
            }
        }

        worldRenderer.increaseTrianglesCount(numberOfRenderedTriangles);
        worldRenderer.increaseNotReadyChunkCount(numberOfChunksThatAreNotReadyYet);

        chunkMaterial.deactivateFeature(ShaderProgramFeature.FEATURE_ALPHA_REJECT);

        PerformanceMonitor.endActivity();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.NORMAL_MAPPING:
                normalMappingIsEnabled = renderingConfig.isNormalMapping();
                if (normalMappingIsEnabled) {
                    addDesiredStateChange(setTerrainNormalsInputTexture);
                } else {
                    removeDesiredStateChange(setTerrainNormalsInputTexture);
                }
                break;
            case RenderingConfig.PARALLAX_MAPPING:
                parallaxMappingIsEnabled = renderingConfig.isParallaxMapping();
                if (parallaxMappingIsEnabled) {
                    addDesiredStateChange(setTerrainHeightInputTexture);
                } else {
                    removeDesiredStateChange(setTerrainHeightInputTexture);
                }
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }

        worldRenderer.requestTaskListRefresh();
    }
}
