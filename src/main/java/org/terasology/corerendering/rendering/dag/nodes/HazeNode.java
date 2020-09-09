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
 * This class is a thin facade in front of the BlurNode class it inherits from.
 * <p>
 * Instances of this class specialize the blur operation to render a "Haze" layer, combined later in the pipeline to
 * progressively fade the rendered world into the backdrop.
 * <p>
 * I.e. if the sky is pink at sunset, faraway hills will fade into pink as they get further away from the camera.
 */
public class HazeNode extends BlurNode {
    public static final SimpleUri INTERMEDIATE_HAZE_FBO_URI = new SimpleUri("engine:fbo.intermediateHaze");
    public static final SimpleUri FINAL_HAZE_FBO_URI = new SimpleUri("engine:fbo.finalHaze");
    private static final float BLUR_RADIUS = 8.0f;

    private final RenderingConfig renderingConfig;

    /**
     * Initializes the HazeNode instance.
     *
     * @param outputFbo The output fbo, to store the blurred image.
     */
    public HazeNode(String nodeUri, Name providingModule, Context context, FBO outputFbo) {
        super(nodeUri, context, providingModule, outputFbo, BLUR_RADIUS);

        renderingConfig = context.get(Config.class).getRendering();
        requiresCondition(renderingConfig::isInscattering);
        renderingConfig.subscribe(RenderingConfig.INSCATTERING, this);
    }
}
