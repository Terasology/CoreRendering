// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.rendering.opengl.BaseFboManager;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.gestalt.naming.Name;

/**
 * Extends the DownSamplerNode class adding setup conditions and fbo configs needed to calculate the exposure value.
 * <p>
 * Specifically: A) it override the setupConditions() method so that instances of this class are enabled if
 * isEyeAdaptation() returns true B) it provide a number of FBOConfigs used to downsample the rendering multiple times,
 * down to 1x1 pixels
 * <p>
 * Once the rendering achieved so far has been downsampled to a 1x1 pixel image the RGB values of the pixel effectively
 * encode the average brightness of the rendering, which in turn is used to tweak the exposure parameter later nodes
 * use.
 */
public class DownSamplerForExposureNode extends DownSamplerNode {
    public static final FboConfig FBO_16X16_CONFIG = new FboConfig(new SimpleUri("engine:fbo.16x16px"), 16, 16,
            FBO.Type.DEFAULT);
    public static final FboConfig FBO_8X8_CONFIG = new FboConfig(new SimpleUri("engine:fbo.8x8px"), 8, 8,
            FBO.Type.DEFAULT);
    public static final FboConfig FBO_4X4_CONFIG = new FboConfig(new SimpleUri("engine:fbo.4x4px"), 4, 4,
            FBO.Type.DEFAULT);
    public static final FboConfig FBO_2X2_CONFIG = new FboConfig(new SimpleUri("engine:fbo.2x2px"), 2, 2,
            FBO.Type.DEFAULT);
    public static final FboConfig FBO_1X1_CONFIG = new FboConfig(new SimpleUri("engine:fbo.1x1px"), 1, 1,
            FBO.Type.DEFAULT);

    public DownSamplerForExposureNode(String nodeUri, Name providingModule, Context context,
                                      BaseFboManager inputFboManager,
                                      FboConfig outputFboConfig, BaseFboManager outputFboManager) {
        super(nodeUri, context, providingModule, inputFboManager, outputFboConfig, outputFboManager);

        RenderingConfig renderingConfig = context.get(Config.class).getRendering();
        requiresCondition(renderingConfig::isEyeAdaptation);

        renderingConfig.subscribe(RenderingConfig.EYE_ADAPTATION, this);
    }
}
