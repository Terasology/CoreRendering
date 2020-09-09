// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.lwjgl.opengl.Display;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import static org.lwjgl.opengl.GL11.glViewport;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.opengl.OpenGLUtils.renderFullscreenQuad;
import static org.terasology.engine.rendering.world.WorldRenderer.RenderingStage.LEFT_EYE;
import static org.terasology.engine.rendering.world.WorldRenderer.RenderingStage.MONO;

public class OutputToScreenNode extends ConditionDependentNode {
    private static final ResourceUrn DEFAULT_TEXTURED_MATERIAL_URN = new ResourceUrn("engine:prog.defaultTextured");

    private final DisplayResolutionDependentFbo displayResolutionDependentFBOs;

    private FBO lastUpdatedGBuffer;
    private FBO staleGBuffer;

    private StateChange bindFbo;

    public OutputToScreenNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);

        requiresCondition(() -> worldRenderer.getCurrentRenderStage() == MONO || worldRenderer.getCurrentRenderStage() == LEFT_EYE);

    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new EnableMaterial(DEFAULT_TEXTURED_MATERIAL_URN));
        bindFbo = new SetInputTextureFromFbo(0, this.getInputFboData(1), ColorTexture, displayResolutionDependentFBOs
                , DEFAULT_TEXTURED_MATERIAL_URN, "texture");
        addDesiredStateChange(bindFbo);

        lastUpdatedGBuffer = getInputBufferPairConnection(1).getBufferPair().getPrimaryFbo();
        staleGBuffer = getInputBufferPairConnection(1).getBufferPair().getSecondaryFbo();
    }

    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());
        // The way things are set-up right now, we can have FBOs that are not the same size as the display (if scale
        // != 100%).
        // However, when drawing the final image to the screen, we always want the viewport to match the size of
        // display,
        // and not that of some FBO. Hence, we are manually setting the viewport via glViewport over here.
        glViewport(0, 0, Display.getWidth(), Display.getHeight());
        renderFullscreenQuad();
        PerformanceMonitor.endActivity();
    }

    @Override
    public void handleCommand(String command, String... arguments) {
        switch (command) {
            case "setFbo":
                if (arguments.length != 1) {
                    throw new RuntimeException("Invalid number of arguments; expected 1, received " + arguments.length + "!");
                }

                FBO fbo;
                switch (arguments[0]) {
                    case "engine:fbo.gBuffer":
                    case "engine:fbo.lastUpdatedGBuffer":
                        fbo = lastUpdatedGBuffer;
                        break;
                    case "engine:fbo.staleGBuffer":
                        fbo = staleGBuffer;
                        break;
                    default:
                        fbo = displayResolutionDependentFBOs.get(new SimpleUri(arguments[0]));

                        if (fbo == null) {
                            throw new RuntimeException(("No FBO is associated with URI '" + arguments[0] + "'"));
                        }

                        break;
                }
                setFbo(fbo);

                break;
            default:
                throw new RuntimeException("Unrecognized command: '" + command + "'");
        }
    }

    private void setFbo(FBO fbo) {
        removeDesiredStateChange(bindFbo);
        bindFbo = new SetInputTextureFromFbo(0, fbo, ColorTexture, displayResolutionDependentFBOs,
                DEFAULT_TEXTURED_MATERIAL_URN, "texture");
        addDesiredStateChange(bindFbo);
        worldRenderer.requestTaskListRefresh();
    }
}
