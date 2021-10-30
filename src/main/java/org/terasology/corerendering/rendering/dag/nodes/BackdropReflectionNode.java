// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Vector3f;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.assets.mesh.SphereBuilder;
import org.terasology.engine.rendering.backdrop.BackdropProvider;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.DisableDepthWriting;
import org.terasology.engine.rendering.dag.stateChanges.EnableFaceCulling;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.ReflectedCamera;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.world.WorldRenderer;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.nui.properties.Range;

import static org.terasology.corerendering.rendering.dag.nodes.BackdropNode.getAllWeatherZenith;

/**
 * An instance of this class is responsible for rendering a reflected backdrop (usually the sky) into the
 * "engine:sceneReflected" buffer. The content of the buffer is later used to render the reflections
 * on the water surface.
 *
 * This class could potentially be used also for other reflecting surfaces, i.e. metal, but it only works
 * for horizontal surfaces.
 *
 * Instances of this class are not dependent on the Video Settings or any other conditions. They can be disabled
 * by using method Node.setEnabled(boolean) or by removing the instance from the Render Graph.
 *
 */
public class BackdropReflectionNode extends AbstractNode {
    public static final SimpleUri REFLECTED_FBO_URI = new SimpleUri("engine:fbo.sceneReflected");
    private static final ResourceUrn SKY_MATERIAL_URN = new ResourceUrn("CoreRendering:sky");
    private static final int RADIUS = 1024;
    private static final int SLICES = 16;
    private static final int STACKS = 128;

    private BackdropProvider backdropProvider;
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
    private final WorldRenderer renderer;

    /**
     * Internally requires the "engine:sceneReflected" buffer, stored in the (display) resolution-dependent FBO manager.
     * This is a default, half-scale buffer inclusive of a depth buffer FBO. See FboConfig and ScalingFactors for details
     * on possible FBO configurations.
     *
     * This method also requests the material using the "sky" shaders (vertex, fragment) to be enabled.
     */
    public BackdropReflectionNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);
        addOutputFboConnection(1);
        renderer = context.get(WorldRenderer.class);

        SphereBuilder builder = new SphereBuilder();
        sphereMesh = Assets.generateAsset(builder
                        .setVerticalCuts(STACKS)
                        .setHorizontalCuts(SLICES)
                        .setRadius(RADIUS)
                        .setTextured(true).build(),
                Mesh.class);
    }

    @Override
    public void setDependencies(Context context) {
        backdropProvider = context.get(BackdropProvider.class);

        Camera activeCamera = renderer.getActiveCamera();
        addDesiredStateChange(new ReflectedCamera(activeCamera));

        FBO reflectedFbo = getInputFboData(1);
        addDesiredStateChange(new BindFbo(reflectedFbo));
        addOutputFboConnection(1, reflectedFbo);
        addDesiredStateChange(new SetViewportToSizeOf(reflectedFbo));
        addDesiredStateChange(new EnableFaceCulling());
        addDesiredStateChange(new DisableDepthWriting());
        addDesiredStateChange(new EnableMaterial(SKY_MATERIAL_URN));

        skyMaterial = getMaterial(SKY_MATERIAL_URN);

        int textureSlot = 0;
        addDesiredStateChange(new SetInputTexture2D(textureSlot++, "engine:sky90", SKY_MATERIAL_URN, "texSky90"));
        addDesiredStateChange(new SetInputTexture2D(textureSlot, "engine:sky180", SKY_MATERIAL_URN, "texSky180"));
    }

    /**
     * Renders the sky, reflected, into the buffers attached to the "engine:sceneReflected" FBO. It is used later,
     * to render horizontal reflective surfaces, i.e. water.
     *
     * Notice that this method clears the FBO, both its color and depth attachments. Earlier nodes using the
     * same buffers beware.
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        // Common Shader Parameters

        sunDirection = backdropProvider.getSunDirection(false);
        turbidity = backdropProvider.getTurbidity();

        skyMaterial.setFloat("daylight", backdropProvider.getDaylight(), true);
        skyMaterial.setFloat3("sunVec", sunDirection, true);

        // Specific Shader Parameters

        skyMaterial.setFloat3("zenith", getAllWeatherZenith(sunDirection.y, turbidity), true);
        skyMaterial.setFloat("turbidity", turbidity, true);
        skyMaterial.setFloat("colorExp", backdropProvider.getColorExp(), true);
        skyMaterial.setFloat4("skySettings", sunExponent, moonExponent, skyDaylightBrightness, skyNightBrightness, true);

        Camera camera = renderer.getActiveCamera();
        skyMaterial.setMatrix4("projectionMatrix", camera.getProjectionMatrix());
        skyMaterial.setMatrix4("modelViewMatrix", camera.getNormViewMatrix());

        // Actual Node Processing

        sphereMesh.render();

        PerformanceMonitor.endActivity();
    }
}
