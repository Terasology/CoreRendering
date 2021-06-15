// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.core.subsystem.DisplayDevice;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.StateChange;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.utilities.Assets;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;

import static org.lwjgl.opengl.GL11.glViewport;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.ColorTexture;
import static org.terasology.engine.rendering.world.WorldRenderer.RenderingStage.LEFT_EYE;
import static org.terasology.engine.rendering.world.WorldRenderer.RenderingStage.MONO;

public class OutputToScreenNode extends ConditionDependentNode {
    private static final ResourceUrn OUTPUT_TEXTURED_MATERIAL_URN = new ResourceUrn("CoreRendering:outputPass");

    private DisplayResolutionDependentFbo displayResolutionDependentFBOs;
    private DisplayDevice displayDevice;
    private Mesh renderQuad;

    private FBO lastUpdatedGBuffer;
    private FBO staleGBuffer;

    private StateChange bindFbo;

    public OutputToScreenNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
        displayDevice = context.get(DisplayDevice.class);
        requiresCondition(() -> worldRenderer.getCurrentRenderStage() == MONO || worldRenderer.getCurrentRenderStage() == LEFT_EYE);
        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));

    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new EnableMaterial(OUTPUT_TEXTURED_MATERIAL_URN));
        bindFbo = new SetInputTextureFromFbo(0, this.getInputFboData(1), ColorTexture, displayResolutionDependentFBOs, OUTPUT_TEXTURED_MATERIAL_URN, "target");
        addDesiredStateChange(bindFbo);

        lastUpdatedGBuffer = getInputBufferPairConnection(1).getBufferPair().getPrimaryFbo();
        staleGBuffer = getInputBufferPairConnection(1).getBufferPair().getSecondaryFbo();
    }

    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());
        // The way things are set-up right now, we can have FBOs that are not the same size as the display (if scale != 100%).
        // However, when drawing the final image to the screen, we always want the viewport to match the size of display,
        // and not that of some FBO. Hence, we are manually setting the viewport via glViewport over here.
        glViewport(0, 0, displayDevice.getWidth(), displayDevice.getHeight());
        this.renderQuad.render();
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
        bindFbo = new SetInputTextureFromFbo(0, fbo, ColorTexture, displayResolutionDependentFBOs, OUTPUT_TEXTURED_MATERIAL_URN, "target");
        addDesiredStateChange(bindFbo);
        worldRenderer.requestTaskListRefresh();
    }
}
