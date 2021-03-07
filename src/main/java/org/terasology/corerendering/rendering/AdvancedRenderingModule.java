/*
 * Copyright 2020 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.corerendering.rendering;

import org.terasology.corerendering.rendering.dag.nodes.AmbientOcclusionNode;
import org.terasology.corerendering.rendering.dag.nodes.BloomBlurNode;
import org.terasology.corerendering.rendering.dag.nodes.BlurredAmbientOcclusionNode;
import org.terasology.corerendering.rendering.dag.nodes.BufferClearingNode;
import org.terasology.corerendering.rendering.dag.nodes.HazeNode;
import org.terasology.corerendering.rendering.dag.nodes.HighPassNode;
import org.terasology.corerendering.rendering.dag.nodes.LightShaftsNode;
import org.terasology.corerendering.rendering.dag.nodes.ShadowMapNode;
import org.terasology.engine.context.Context;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.ModuleRendering;
import org.terasology.engine.rendering.dag.Node;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.rendering.opengl.fbms.ImmutableFbo;
import org.terasology.engine.rendering.opengl.fbms.ShadowMapResolutionDependentFbo;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.terasology.engine.rendering.opengl.ScalingFactors.HALF_SCALE;
import static org.terasology.engine.rendering.opengl.ScalingFactors.ONE_16TH_SCALE;
import static org.terasology.engine.rendering.opengl.ScalingFactors.ONE_32TH_SCALE;
import static org.terasology.engine.rendering.opengl.ScalingFactors.ONE_8TH_SCALE;
import static org.terasology.engine.rendering.opengl.ScalingFactors.QUARTER_SCALE;

public class AdvancedRenderingModule extends ModuleRendering {
    private static int initializationPriority = 2;

    private DisplayResolutionDependentFbo displayResolutionDependentFbo;
    private ShadowMapResolutionDependentFbo shadowMapResolutionDependentFbo;
    private ImmutableFbo immutableFbo;

    private ShadowMapNode shadowMapNode;

    // Created in renderingModuleRegistry trough reflection and Constructor calling
    public AdvancedRenderingModule(Context context) {
        super(context);
        setInitializationPriority(initializationPriority);
    }

    public void initialise() {
        super.initialise();

        initAdvancedRendering();
    }

    private void initAdvancedRendering() {
        immutableFbo = new ImmutableFbo();
        context.put(ImmutableFbo.class, immutableFbo);

        shadowMapResolutionDependentFbo = new ShadowMapResolutionDependentFbo();
        context.put(ShadowMapResolutionDependentFbo.class, shadowMapResolutionDependentFbo);

        displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);

        addHaze();

        addShadowMap();

        addAmbientOcclusion();

        addLightShafts();

        addBloomNodes();
    }

    private void addHaze() {
        Node backdropNode = renderGraph.findAka("backdrop");
        // Node lastUpdatedGBufferClearingNode = renderGraph.findAka("lastUpdatedGBufferClearing");

        FboConfig intermediateHazeConfig = new FboConfig(HazeNode.INTERMEDIATE_HAZE_FBO_URI, ONE_16TH_SCALE,
            FBO.Type.DEFAULT);
        FBO intermediateHazeFbo = displayResolutionDependentFbo.request(intermediateHazeConfig);

        HazeNode intermediateHazeNode = new HazeNode("intermediateHazeNode", providingModule, context,
            intermediateHazeFbo);
        // TODO I introduce new BufferPairConnection but I have to fetch it from the old system. This must be removed
        //  when every node uses new system
        // make this implicit
        // intermediateHazeNode.addInputBufferPairConnection(1, new Pair<FBO,FBO>(displayResolutionDependentFbo
        // .getGBufferPair().getLastUpdatedFbo(),
        //                                                                          displayResolutionDependentFbo
        //                                                                          .getGBufferPair().getStaleFbo()));
        renderGraph.connectBufferPair(backdropNode, 1, intermediateHazeNode, 1);
        intermediateHazeNode.addInputFboConnection(1,
            backdropNode.getOutputBufferPairConnection(1).getBufferPair().getPrimaryFbo());
        intermediateHazeNode.addOutputBufferPairConnection(1,
            backdropNode.getOutputBufferPairConnection(1).getBufferPair());
        renderGraph.addNode(intermediateHazeNode);

        FboConfig finalHazeConfig = new FboConfig(HazeNode.FINAL_HAZE_FBO_URI, ONE_32TH_SCALE, FBO.Type.DEFAULT);
        FBO finalHazeFbo = displayResolutionDependentFbo.request(finalHazeConfig);

        HazeNode finalHazeNode = new HazeNode("finalHazeNode", providingModule, context, finalHazeFbo);
        renderGraph.connectBufferPair(intermediateHazeNode, 1, finalHazeNode, 1);
        renderGraph.connectFbo(intermediateHazeNode, 1, finalHazeNode, 1);
        // Hack because HazeNode extends Blur which is a reusable node and we can't tailor its code to this need
        // finalHazeNode.addOutputBufferPairConnection(1, intermediateHazeNode.getOutputBufferPairConnection(1)
        // .getBufferPair());
        renderGraph.addNode(finalHazeNode);

        Node opaqueObjectsNode = renderGraph.findAka("opaqueObjects");
        Node opaqueBlocksNode = renderGraph.findAka("opaqueBlocks");
        Node alphaRejectBlocksNode = renderGraph.findAka("alphaRejectBlocks");
        Node overlaysNode = renderGraph.findAka("overlays");
        renderGraph.reconnectInputBufferPairToOutput(finalHazeNode, 1, opaqueObjectsNode, 1);
        renderGraph.reconnectInputBufferPairToOutput(finalHazeNode, 1, opaqueBlocksNode, 1);
        renderGraph.reconnectInputBufferPairToOutput(finalHazeNode, 1, alphaRejectBlocksNode, 1);
        renderGraph.reconnectInputBufferPairToOutput(finalHazeNode, 1, overlaysNode, 1);

        Node prePostCompositeNode = renderGraph.findAka("prePostComposite");
        renderGraph.connectFbo(finalHazeNode, 1, prePostCompositeNode, 3);
    }

    private void addShadowMap() {
        FboConfig shadowMapConfig = new FboConfig(ShadowMapNode.SHADOW_MAP_FBO_URI, FBO.Type.NO_COLOR).useDepthBuffer();
        BufferClearingNode shadowMapClearingNode = new BufferClearingNode("shadowMapClearingNode", providingModule,
            context,
            shadowMapConfig, shadowMapResolutionDependentFbo, GL_DEPTH_BUFFER_BIT);
        renderGraph.addNode(shadowMapClearingNode);

        shadowMapNode = new ShadowMapNode("shadowMapNode", providingModule, context);
        renderGraph.connectFbo(shadowMapClearingNode, 1, shadowMapNode, 1);
        renderGraph.addNode(shadowMapNode);

        Node deferredMainLightNode = renderGraph.findNode("CoreRendering:deferredMainLightNode");
        renderGraph.connectFbo(shadowMapNode, 1, deferredMainLightNode, 1);
    }

    private void addAmbientOcclusion() {
        Node opaqueObjectsNode = renderGraph.findNode("CoreRendering:opaqueObjectsNode");
        Node opaqueBlocksNode = renderGraph.findAka("opaqueBlocks");
        Node alphaRejectBlocksNode = renderGraph.findAka("alphaRejectBlocks");
        Node applyDeferredLightingNode = renderGraph.findAka("applyDeferredLighting");

        Node ambientOcclusionNode = new AmbientOcclusionNode("ambientOcclusionNode", providingModule, context);
        renderGraph.connectBufferPair(applyDeferredLightingNode, 1, ambientOcclusionNode, 1);
        renderGraph.connectRunOrder(opaqueObjectsNode, 3, ambientOcclusionNode, 1);
        renderGraph.connectRunOrder(opaqueBlocksNode, 3, ambientOcclusionNode, 2);
        renderGraph.connectRunOrder(alphaRejectBlocksNode, 4, ambientOcclusionNode, 3);
        renderGraph.addNode(ambientOcclusionNode);

        Node blurredAmbientOcclusionNode = new BlurredAmbientOcclusionNode("blurredAmbientOcclusionNode",
            providingModule, context);
        renderGraph.connectBufferPair(ambientOcclusionNode, 1, blurredAmbientOcclusionNode, 1);
        renderGraph.connectFbo(ambientOcclusionNode, 1, blurredAmbientOcclusionNode, 1);
        renderGraph.addNode(blurredAmbientOcclusionNode);

        Node prePostCompositeNode = renderGraph.findAka("prePostComposite");
        renderGraph.connectFbo(blurredAmbientOcclusionNode, 1, prePostCompositeNode, 1);
    }

    private void addLightShafts() {
        // Light shafts
        Node simpleBlendMaterialsNode = renderGraph.findNode("CoreRendering:simpleBlendMaterialsNode");

        LightShaftsNode lightShaftsNode = new LightShaftsNode("lightShaftsNode", providingModule, context);
        renderGraph.connectBufferPair(simpleBlendMaterialsNode, 1, lightShaftsNode, 1);
        renderGraph.addNode(lightShaftsNode);

        Node initialPostProcessing = renderGraph.findNode("CoreRendering:initialPostProcessingNode");
        renderGraph.connectFbo(lightShaftsNode, 1, initialPostProcessing, 1);
    }

    private void addBloomNodes() {
        // Bloom Effect: one high-pass filter and three blur passes
        Node simpleBlendMaterialsNode = renderGraph.findNode("CoreRendering:simpleBlendMaterialsNode");

        Node highPassNode = new HighPassNode("highPassNode", providingModule, context);
        renderGraph.connectBufferPair(simpleBlendMaterialsNode, 1, highPassNode, 1);
        renderGraph.addNode(highPassNode);

        FboConfig halfScaleBloomConfig = new FboConfig(BloomBlurNode.HALF_SCALE_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT);
        FBO halfScaleBloomFbo = displayResolutionDependentFbo.request(halfScaleBloomConfig);

        // TODO once everything is new system based, update halfscaleblurrednode's input obtaining
        BloomBlurNode halfScaleBlurredBloomNode = new BloomBlurNode("halfScaleBlurredBloomNode", providingModule,
            context, halfScaleBloomFbo);
        // halfScaleBlurredBloomNode.addInputFboConnection(1, displayResolutionDependentFbo.get(HighPassNode
        // .HIGH_PASS_FBO_URI));
        renderGraph.connectFbo(highPassNode, 1, halfScaleBlurredBloomNode, 1);
        renderGraph.addNode(halfScaleBlurredBloomNode);

        FboConfig quarterScaleBloomConfig = new FboConfig(BloomBlurNode.QUARTER_SCALE_FBO_URI, QUARTER_SCALE,
            FBO.Type.DEFAULT);
        FBO quarterScaleBloomFbo = displayResolutionDependentFbo.request(quarterScaleBloomConfig);

        BloomBlurNode quarterScaleBlurredBloomNode = new BloomBlurNode("quarterScaleBlurredBloomNode",
            providingModule, context, quarterScaleBloomFbo);
        renderGraph.connectFbo(halfScaleBlurredBloomNode, 1, quarterScaleBlurredBloomNode, 1);
        renderGraph.addNode(quarterScaleBlurredBloomNode);

        FboConfig one8thScaleBloomConfig = new FboConfig(BloomBlurNode.ONE_8TH_SCALE_FBO_URI, ONE_8TH_SCALE,
            FBO.Type.DEFAULT);
        FBO one8thScaleBloomFbo = displayResolutionDependentFbo.request(one8thScaleBloomConfig);

        BloomBlurNode one8thScaleBlurredBloomNode = new BloomBlurNode("one8thScaleBlurredBloomNode", providingModule,
            context, one8thScaleBloomFbo);
        renderGraph.connectFbo(quarterScaleBlurredBloomNode, 1, one8thScaleBlurredBloomNode, 1);
        renderGraph.addNode(one8thScaleBlurredBloomNode);

        Node initialPostProcessing = renderGraph.findNode("CoreRendering:initialPostProcessingNode");
        renderGraph.connectFbo(one8thScaleBlurredBloomNode, 1, initialPostProcessing, 2);
    }

    public Camera getLightCamera() {
        //FIXME: remove this methodw
        return shadowMapNode.shadowMapCamera;
    }
}
