// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.dag.AbstractNode;
import org.terasology.engine.rendering.opengl.PBO;
import org.terasology.engine.rendering.opengl.ScreenGrabber;
import org.terasology.gestalt.naming.Name;
import org.terasology.math.TeraMath;
import org.terasology.nui.properties.Range;

/**
 * An instance of this node takes advantage of a downsampled version of the scene,
 * calculates its relative luminance (1) and updates the exposure parameter of the
 * ScreenGrabber accordingly.
 *
 * Notice that while this node takes advantage of the content of an FBO, it
 * doesn't actually render anything.
 *
 * (1) See https://en.wikipedia.org/wiki/Luma_(video)#Use_of_relative_luminance
 */
public class UpdateExposureNode extends AbstractNode {
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 10.0f)
    private float hdrExposureDefault = 5f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 10.0f)
    private float hdrMaxExposure = 8.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 10.0f)
    private float hdrMinExposure = 1.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 4.0f)
    private float hdrTargetLuminance = 1.0f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.0f, max = 0.5f)
    private float hdrExposureAdjustmentSpeed = 0.05f;

    private ScreenGrabber screenGrabber;

    private RenderingConfig renderingConfig;
    private int downSampledSceneId;
    private PBO writeOnlyPbo;   // PBOs are 1x1 pixels buffers used to read GPU data back into the CPU.
                                // This data is then used in the context of eye adaptation.

    public UpdateExposureNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        screenGrabber = context.get(ScreenGrabber.class);

        renderingConfig = context.get(Config.class).getRendering();
        // downSampledScene = requiresFbo(DownSamplerForExposureNode.FBO_1X1_CONFIG, context.get(ImmutableFbo.class));
        writeOnlyPbo = new PBO(1, 1);
    }

    @Override
    public void setDependencies(Context context) {
        downSampledSceneId = getInputFboData(1).getId();
    }

    /**
     * If Eye Adaptation is enabled, given the 1-pixel output of the downSamplerNode,
     * calculates the relative luminance of the scene and updates the exposure accordingly.
     *
     * If Eye Adaptation is disabled, sets the exposure to default day/night values.
     */
    // TODO: verify if this can be achieved entirely in the GPU, during tone mapping perhaps?
    @Override
    public void process() {
        if (renderingConfig.isEyeAdaptation()) {
            PerformanceMonitor.startActivity("rendering/" + getUri());

            float[] pixels = new float[3];
            writeOnlyPbo.readBackPixels(buffer -> {
                pixels[0] = (buffer.get(2) & 0xFF) / 255.f;
                pixels[1] = (buffer.get(1) & 0xFF) / 255.f;
                pixels[2] = (buffer.get(0) & 0xFF) / 255.f;
            });
            writeOnlyPbo.copyFromFBO(downSampledSceneId, 1, 1, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE);

            // See: https://en.wikipedia.org/wiki/Luma_(video)#Use_of_relative_luminance for the constants below.
            float currentSceneLuminance = 0.2126f * pixels[0] + 0.7152f * pixels[1] + 0.0722f * pixels[2];

            float targetExposure = hdrMaxExposure;

            if (currentSceneLuminance > 0) {
                targetExposure = hdrTargetLuminance / currentSceneLuminance;
            }

            float maxExposure = hdrMaxExposure;

            if (targetExposure > maxExposure) {
                targetExposure = maxExposure;
            } else if (targetExposure < hdrMinExposure) {
                targetExposure = hdrMinExposure;
            }

            screenGrabber.setExposure(TeraMath.lerp(screenGrabber.getExposure(), targetExposure, hdrExposureAdjustmentSpeed));

            PerformanceMonitor.endActivity();
        } else {
            screenGrabber.setExposure(hdrExposureDefault);
        }
    }
}
