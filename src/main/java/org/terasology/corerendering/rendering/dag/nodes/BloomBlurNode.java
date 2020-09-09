// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.gestalt.naming.Name;

/**
 * If bloom is enabled via the rendering settings, this method generates the blurred images needed for the bloom shader
 * effect and stores them in their own frame buffers.
 * <p>
 * This effects renders adds fringes (or "feathers") of light to areas of intense brightness. This in turn give the
 * impression of those areas partially overwhelming the camera or the eye.
 * <p>
 * For more information see: http://en.wikipedia.org/wiki/Bloom_(shader_effect)
 */
public class BloomBlurNode extends BlurNode {
    public static final SimpleUri HALF_SCALE_FBO_URI = new SimpleUri("engine:fbo.halfScaleBlurredBloom");
    public static final SimpleUri QUARTER_SCALE_FBO_URI = new SimpleUri("engine:fbo.quarterScaleBlurredBloom");
    public static final SimpleUri ONE_8TH_SCALE_FBO_URI = new SimpleUri("engine:fbo.one8thScaleBlurredBloom");
    private static final float BLUR_RADIUS = 12.0f;

    /**
     * Constructs a BloomBlurNode instance. This method must be called once shortly after instantiation to fully
     * initialize the node and make it ready for rendering.
     *
     * @param outputFbo The output fbo, to store the blurred image.
     */
    public BloomBlurNode(String nodeUri, Name providingModule, Context context, FBO outputFbo) {
        super(nodeUri, context, providingModule, outputFbo, BLUR_RADIUS);

        RenderingConfig renderingConfig = context.get(Config.class).getRendering();
        requiresCondition(renderingConfig::isBloom);
        renderingConfig.subscribe(RenderingConfig.BLOOM, this);
    }
}
