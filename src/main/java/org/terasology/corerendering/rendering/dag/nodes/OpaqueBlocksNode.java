// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.config.RenderingDebugConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.AABBRenderer;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.cameras.PerspectiveCamera;
import org.terasology.engine.rendering.cameras.SubmersibleCamera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.WireframeCapable;
import org.terasology.engine.rendering.dag.WireframeTrigger;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
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

import static org.terasology.engine.rendering.primitives.ChunkMesh.RenderPhase.OPAQUE;

/**
 * This node renders the opaque blocks in the world.
 *
 * In a typical world this is the majority of the world's landscape.
 */
public class OpaqueBlocksNode extends AbstractNode implements WireframeCapable, PropertyChangeListener {
    private static final ResourceUrn CHUNK_MATERIAL_URN = new ResourceUrn("CoreRendering:chunk");

    private WorldRenderer worldRenderer;
    private RenderQueuesHelper renderQueues;
    private RenderingConfig renderingConfig;
    private WorldProvider worldProvider;

    private Material chunkMaterial;
    private SetWireframe wireframeStateChange;
    private EnableFaceCulling faceCullingStateChange;
    private RenderingDebugConfig renderingDebugConfig;

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

    public OpaqueBlocksNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        renderQueues = context.get(RenderQueuesHelper.class);
        worldProvider = context.get(WorldProvider.class);
        addOutputBufferPairConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        worldRenderer = context.get(WorldRenderer.class);
        activeCamera = worldRenderer.getActiveCamera();

        // IF wireframe is enabled the WireframeTrigger will remove the face culling state change
        // from the set of desired state changes.
        // The alternative would have been to check here first if wireframe mode is enabled and *if not*
        // add the face culling state change. However, if wireframe *is* enabled, the WireframeTrigger
        // would attempt to remove the face culling state even though it isn't there, relying on the
        // quiet behaviour of Set.remove(nonExistentItem). We therefore favored the first solution.
        faceCullingStateChange = new EnableFaceCulling();
        addDesiredStateChange(faceCullingStateChange);

        wireframeStateChange = new SetWireframe(true);
        renderingDebugConfig = context.get(Config.class).getRendering().getDebug();
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
            removeDesiredStateChange(faceCullingStateChange);
            addDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    public void disableWireframe() {
        if (getDesiredStateChanges().contains(wireframeStateChange)) {
            addDesiredStateChange(faceCullingStateChange);
            removeDesiredStateChange(wireframeStateChange);
            worldRenderer.requestTaskListRefresh();
        }
    }

    /**
     * Renders the world's opaque blocks, effectively, the world's landscape.
     * Does not render semi-transparent blocks, i.e. semi-transparent vegetation.
     *
     * If RenderingDebugConfig.isRenderChunkBoundingBoxes() returns true
     * this method also draws wireframe boxes around chunks, displaying
     * their boundaries.
     *
     * Finally, takes advantage of the two methods
     *
     * - WorldRenderer.increaseTrianglesCount(int)
     * - WorldRenderer.increaseNotReadyChunkCount(int)
     *
     * to publish some statistics over its own activity.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Common Shader Parameters

        chunkMaterial.setFloat("time", worldProvider.getTime().getDays(), true);

        // Specific Shader Parameters

        chunkMaterial.setFloat("clip", 0.0f, true);

        if (parallaxMappingIsEnabled) {
            chunkMaterial.setFloat4("parallaxProperties", parallaxBias, parallaxScale, 0.0f, 0.0f, true);
        }

        // Actual Node Processing

        final Vector3f cameraPosition = activeCamera.getPosition();

        int numberOfRenderedTriangles = 0;
        int numberOfChunksThatAreNotReadyYet = 0;

        Matrix4f modelViewMatrix = new Matrix4f();
        Matrix4f model = new Matrix4f();
        Matrix3f normalMatrix = new Matrix3f();
        chunkMaterial.setMatrix4("projectionMatrix", activeCamera.getProjectionMatrix(), true);

        while (renderQueues.chunksOpaque.size() > 0) {
            RenderableChunk chunk = renderQueues.chunksOpaque.poll();

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
                numberOfRenderedTriangles += chunkMesh.render(OPAQUE);

                if (renderingDebugConfig.isRenderChunkBoundingBoxes()) {
                    try (AABBRenderer renderer = new AABBRenderer(chunk.getAABB())) {
                        renderer.render();
                    }
                }

            } else {
                numberOfChunksThatAreNotReadyYet++;
            }
        }

        worldRenderer.increaseTrianglesCount(numberOfRenderedTriangles);
        worldRenderer.increaseNotReadyChunkCount(numberOfChunksThatAreNotReadyYet);

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
