// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingDebugConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.assets.mesh.SphereBuilder;
import org.terasology.engine.rendering.backdrop.BackdropProvider;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.WireframeCapable;
import org.terasology.engine.rendering.dag.WireframeTrigger;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.DisableDepthWriting;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetFacesToCull;
import org.terasology.engine.rendering.dag.stateChanges.SetFboWriteMask;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetWireframe;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import static org.lwjgl.opengl.GL11.GL_FRONT;

/**
 * Renders the backdrop.
 *
 * In this implementation the backdrop consists of a spherical mesh (a skysphere)
 * on which two sky textures are projected, one for the day and one for the night.
 * The two textures cross-fade as the day turns to night and viceversa.
 *
 * The shader also procedurally adds a main light (sun/moon) in the form of a blurred disc.
 */
public class BackdropNode extends AbstractNode implements WireframeCapable {
    private static final ResourceUrn SKY_MATERIAL_URN = new ResourceUrn("CoreRendering:sky");
    private static final int SLICES = 16;
    private static final int STACKS = 128;
    private static final int RADIUS = 1024;

    SphereBuilder builder = new SphereBuilder();

    private WorldRenderer worldRenderer;
    private BackdropProvider backdropProvider;

    private SetWireframe wireframeStateChange;

    private Material skyMaterial;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 1.0f, max = 8192.0f)
    private float sunExponent = 512.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 1.0f, max = 8192.0f)
    private float moonExponent = 256.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 10.0f)
    private float skyDaylightBrightness = 0.6f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 10.0f)
    private float skyNightBrightness = 1.0f;

    @SuppressWarnings("FieldCanBeLocal")
    private Vector3f sunDirection;
    @SuppressWarnings("FieldCanBeLocal")
    private float turbidity;

    private final Mesh sphereMesh;
    public BackdropNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        backdropProvider = context.get(BackdropProvider.class);

        wireframeStateChange = new SetWireframe(true);

        skyMaterial = getMaterial(SKY_MATERIAL_URN);

        sphereMesh = Assets.generateAsset(builder
                        .setVerticalCuts(SLICES)
                        .setHorizontalCuts(STACKS)
                        .setRadius(RADIUS)
                        .setTextured(true).build(),
                Mesh.class);
    }

    @Override
    public void setDependencies(Context context) {
        worldRenderer = context.get(WorldRenderer.class);
        Camera activeCamera = worldRenderer.getActiveCamera();

        sphereMesh.reload(builder.setRadius(activeCamera.getzFar() < RADIUS ? activeCamera.getzFar() : RADIUS).build());

        RenderingDebugConfig renderingDebugConfig = context.get(Config.class).getRendering().getDebug();
        new WireframeTrigger(renderingDebugConfig, this);

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        addDesiredStateChange(new BindFbo(lastUpdatedGBuffer));

        // addOutputFboConnection(1, lastUpdatedGBuffer);
        addOutputBufferPairConnection(1, bufferPairConnection);

        addDesiredStateChange(new SetFboWriteMask(lastUpdatedGBuffer, true, false, false));

        addDesiredStateChange(new EnableMaterial(SKY_MATERIAL_URN));

        // By disabling the writing to the depth buffer the sky will always have a depth value
        // set by the latest glClear statement.
        addDesiredStateChange(new DisableDepthWriting());

        // Note: culling GL_FRONT polygons is necessary as we are inside the sphere and
        //       due to vertex ordering the polygons we do see are the GL_BACK ones.
        addDesiredStateChange(new EnableFaceCulling());
        addDesiredStateChange(new SetFacesToCull(GL_FRONT));

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:sky90", SKY_MATERIAL_URN, "texSky90"));
        addDesiredStateChange(new SetInputTexture2D(textureSlot, "engine:sky180", SKY_MATERIAL_URN, "texSky180"));
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
     * Renders the backdrop of the scene - in this implementation: the skysphere.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Common Shader Parameters

        sunDirection = backdropProvider.getSunDirection(false);
        turbidity = backdropProvider.getTurbidity();

        skyMaterial.setFloat("daylight", backdropProvider.getDaylight(), true);
        skyMaterial.setFloat3("sunVec", sunDirection, true);

        // Shader Parameters

        skyMaterial.setFloat3("zenith", getAllWeatherZenith(backdropProvider.getSunDirection(false).y, turbidity),
                true);
        skyMaterial.setFloat("turbidity", turbidity, true);
        skyMaterial.setFloat("colorExp", backdropProvider.getColorExp(), true);
        skyMaterial.setFloat4("skySettings", sunExponent, moonExponent, skyDaylightBrightness, skyNightBrightness, true);

        Camera camera = worldRenderer.getActiveCamera();
        skyMaterial.setMatrix4("projectionMatrix", camera.getProjectionMatrix());
        skyMaterial.setMatrix4("modelViewMatrix", camera.getNormViewMatrix());

        // Actual Node Processing
        sphereMesh.render();

        PerformanceMonitor.endActivity();
    }

    static Vector3f getAllWeatherZenith(float thetaSunAngle, float turbidity) {
        float thetaSun = (float) Math.acos(thetaSunAngle);
        Vector4f cx1 = new Vector4f(0.0f, 0.00209f, -0.00375f, 0.00165f);
        Vector4f cx2 = new Vector4f(0.00394f, -0.03202f, 0.06377f, -0.02903f);
        Vector4f cx3 = new Vector4f(0.25886f, 0.06052f, -0.21196f, 0.11693f);
        Vector4f cy1 = new Vector4f(0.0f, 0.00317f, -0.00610f, 0.00275f);
        Vector4f cy2 = new Vector4f(0.00516f, -0.04153f, 0.08970f, -0.04214f);
        Vector4f cy3 = new Vector4f(0.26688f, 0.06670f, -0.26756f, 0.15346f);

        float t2 = turbidity * turbidity;
        float chi = (4.0f / 9.0f - turbidity / 120.0f) * ((float) Math.PI - 2.0f * thetaSun);

        Vector4f theta = new Vector4f(1, thetaSun, thetaSun * thetaSun, thetaSun * thetaSun * thetaSun);

        float why = (4.0453f * turbidity - 4.9710f) * (float) Math.tan(chi) - 0.2155f * turbidity + 2.4192f;
        float x = t2 * cx1.dot(theta) + turbidity * cx2.dot(theta) + cx3.dot(theta);
        float y = t2 * cy1.dot(theta) + turbidity * cy2.dot(theta) + cy3.dot(theta);

        return new Vector3f(why, x, y);
    }
}
