// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.backdrop.BackdropProvider;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.cameras.OrthographicCamera;
import org.terasology.engine.rendering.cameras.PerspectiveCamera;
import org.terasology.engine.rendering.cameras.SubmersibleCamera;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetFacesToCull;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.world.RenderQueuesHelper;
import org.terasology.engine.rendering.world.RenderableWorld;
import org.terasology.engine.world.chunks.RenderableChunk;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.math.TeraMath;


import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.terasology.engine.rendering.primitives.ChunkMesh.RenderPhase.OPAQUE;

/**
 * This node class generates a shadow map used by the lighting step to determine what's in sight of
 * the main light (sun, moon) and what isn't, allowing the display of shadows cast from said light.
 * TODO: generalize to handle more than one light.
 *
 * Instances of this class:
 * - are enabled and disabled depending on the shadow setting in the rendering config.
 * - in VR mode regenerate the shadow map only once per frame rather than once per-eye.
 *
 * Diagram of this node can be viewed from:
 * TODO: move diagram to the wiki when this part of the code is stable
 * - https://docs.google.com/drawings/d/13I0GM9jDFlZv1vNrUPlQuBbaF86RPRNpVfn5q8Wj2lc/edit?usp=sharing
 */
public class ShadowMapNode extends ConditionDependentNode implements PropertyChangeListener {
    public static final SimpleUri SHADOW_MAP_FBO_URI = new SimpleUri("engine:fbo.sceneShadowMap");
    private static final ResourceUrn SHADOW_MAP_MATERIAL_URN = new ResourceUrn("CoreRendering:shadowMap");
    private static final int SHADOW_FRUSTUM_BOUNDS = 200;
    private Material shadowMapMaterial;
    private static final float STEP_SIZE = 50f;

    public Camera shadowMapCamera = new OrthographicCamera(-SHADOW_FRUSTUM_BOUNDS, SHADOW_FRUSTUM_BOUNDS, SHADOW_FRUSTUM_BOUNDS, -SHADOW_FRUSTUM_BOUNDS);

    private BackdropProvider backdropProvider;
    private RenderingConfig renderingConfig;
    private RenderQueuesHelper renderQueues;

    private Camera activeCamera;
    private double texelSize;

    public ShadowMapNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);
        shadowMapMaterial = getMaterial(SHADOW_MAP_MATERIAL_URN);

        renderQueues = context.get(RenderQueuesHelper.class);
        backdropProvider = context.get(BackdropProvider.class);
        renderingConfig = context.get(Config.class).getRendering();
        addDesiredStateChange(new SetFacesToCull(GL_FRONT));

        activeCamera = worldRenderer.getActiveCamera();

        context.get(RenderableWorld.class).setShadowMapCamera(shadowMapCamera);
        shadowMapCamera.setzNear(-500.0f);
        shadowMapCamera.setzFar(500.0f);

        texelSize = calculateTexelSize(renderingConfig.getShadowMapResolution());
        renderingConfig.subscribe(RenderingConfig.SHADOW_MAP_RESOLUTION, this);

        requiresCondition(() -> renderingConfig.isDynamicShadows());
        renderingConfig.subscribe(RenderingConfig.DYNAMIC_SHADOWS, this);
        addOutputFboConnection(1);
    }

    @Override
    public void setDependencies(Context context) {
        shadowMapMaterial = getMaterial(SHADOW_MAP_MATERIAL_URN);

        FBO shadowMapFbo = getInputFboData(1);
        addOutputFboConnection(1, shadowMapFbo);
        addDesiredStateChange(new BindFbo(shadowMapFbo));
        addDesiredStateChange(new SetViewportToSizeOf(shadowMapFbo));
        addDesiredStateChange(new EnableMaterial(SHADOW_MAP_MATERIAL_URN));

        addDesiredStateChange(new EnableFaceCulling());
    }

    private double calculateTexelSize(int shadowMapResolution) {
        return (1.0 / shadowMapResolution) * 2.0; // the 2.0 multiplier is currently a mystery.
    }

    /**
     * Handle changes to the following rendering config properties:
     *
     * - DYNAMIC_SHADOWS
     * - SHADOW_MAP_RESOLUTION
     *
     * It assumes the event gets fired only if one of the property has actually changed.
     *
     * @param event a PropertyChangeEvent instance, carrying information regarding
     *              what property changed, its old value and its new value.
     */
    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.DYNAMIC_SHADOWS:
                super.propertyChange(event);
                break;

            case RenderingConfig.SHADOW_MAP_RESOLUTION:
                int shadowMapResolution = (int) event.getNewValue();
                texelSize = calculateTexelSize(shadowMapResolution);
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }
    }

    /**
     * Re-positions the shadow map camera to loosely match the position of the main light (sun, moon), then
     * writes depth information from that camera into a depth buffer, to be used later to create shadows.
     *
     * The loose match is to avoid flickering: the shadowmap only moves in steps while the main light actually
     * moves continuously.
     *
     * This method is executed within a NodeTask in the Render Tasklist, but its calculations are executed
     * only once per frame. I.e. in VR mode they are executed only when the left eye is processed. This is
     * done in the assumption that we do not need to generate and use a shadow map for each eye as it wouldn't
     * be noticeable.
     */
    @Override
    public void process() {

        GL30.glViewport(0, 0, renderingConfig.getShadowMapResolution(), renderingConfig.getShadowMapResolution());
        GL30.glEnable(GL30.GL_POLYGON_OFFSET_FILL);
        GL30.glPolygonOffset(0,1);

        // TODO: remove this IF statement when VR is handled via parallel nodes, one per eye.
        if (worldRenderer.isFirstRenderingStageForCurrentFrame()) {
            PerformanceMonitor.startActivity("rendering/" + getUri());

            // Actual Node Processing
            positionShadowMapCamera(); // TODO: extract these calculation into a separate node.
            shadowMapMaterial.setMatrix4("projectionMatrix", shadowMapCamera.getProjectionMatrix(), true);

            int numberOfRenderedTriangles = 0;
            int numberOfChunksThatAreNotReadyYet = 0;

            final Vector3f cameraPosition = shadowMapCamera.getPosition();

            Matrix4f modelViewMatrix = new Matrix4f();
            Matrix4f model = new Matrix4f();
            // FIXME: storing chunksOpaqueShadow or a mechanism for requesting a chunk queue for nodes which calls renderChunks method?
            while (renderQueues.chunksOpaqueShadow.size() > 0) {
                RenderableChunk chunk = renderQueues.chunksOpaqueShadow.poll();
                if (chunk.hasMesh()) {
                    model.setTranslation(chunk.getRenderPosition().sub(cameraPosition));
                    modelViewMatrix.set(shadowMapCamera.getViewMatrix()).mul(model);
                    shadowMapMaterial.setMatrix4("modelViewMatrix", modelViewMatrix, true);
                    numberOfRenderedTriangles += chunk.getMesh().render(OPAQUE);

                } else {
                    numberOfChunksThatAreNotReadyYet++;
                }
            }

            worldRenderer.increaseTrianglesCount(numberOfRenderedTriangles);
            worldRenderer.increaseNotReadyChunkCount(numberOfChunksThatAreNotReadyYet);

            PerformanceMonitor.endActivity();
        }
        GL30.glDisable(GL30.GL_POLYGON_OFFSET_FILL);

        GL30.glViewport(0, 0, renderingConfig.getWindowWidth(), renderingConfig.getWindowHeight());
    }

    private void positionShadowMapCamera() {
        // We begin by setting our light coordinates at the player coordinates, ignoring the player's altitude
        Vector3f mainLightPosition = new Vector3f(activeCamera.getPosition().x, 0.0f, activeCamera.getPosition().z); // world-space coordinates
        // This is what causes the shadow map to change infrequently, to prevent flickering.
        // Notice that this is different from what is done above, which is about spatial steps
        // and is related to the player's position and texels.
        Vector3f quantizedMainLightDirection = getQuantizedMainLightDirection(STEP_SIZE);

        // The shadow map camera is placed away from the player, in the direction of the main light.
        Vector3f offsetFromPlayer = new Vector3f(quantizedMainLightDirection);
        offsetFromPlayer.mul(64.0f); // these hardcoded numbers are another mystery.
        mainLightPosition.add(offsetFromPlayer);

        // Finally, we adjust the shadow map camera to look toward the player
        Vector3f fromLightToPlayerDirection = new Vector3f(quantizedMainLightDirection);
        fromLightToPlayerDirection.mul(-1.0f);

        shadowMapCamera.getPosition().set(mainLightPosition);
        shadowMapCamera.getViewingDirection().set(fromLightToPlayerDirection);

        // The shadow projected onto the ground must move in in light-space texel-steps, to avoid causing flickering.
        // That's why we first convert it to the previous frame's light-space coordinates and then back to world-space.
        shadowMapCamera.getViewProjectionMatrix().transformPosition(mainLightPosition); // to light-space
        mainLightPosition.set(Math.floor(mainLightPosition.x / texelSize) * texelSize, 0.0f,Math.floor(mainLightPosition.z / texelSize) * texelSize);
        shadowMapCamera.getInverseViewProjectionMatrix().transformPosition(mainLightPosition); // back to world-space
        shadowMapCamera.getPosition().set(mainLightPosition);
        shadowMapCamera.updateMatrices();

    }


    private Vector3f getQuantizedMainLightDirection(float stepSize) {
        float mainLightAngle = (float) (Math.floor((double) backdropProvider.getSunPositionAngle() * stepSize) / stepSize);
        Vector3f mainLightDirection = new Vector3f(0.0f, Math.cos(mainLightAngle), Math.sin(mainLightAngle));

        // When the sun goes under the horizon we flip the vector, to provide the moon direction, and viceversa.
        if (mainLightDirection.y < 0.0f) {
            mainLightDirection.mul(-1.0f);
        }

        return mainLightDirection;
    }

}
