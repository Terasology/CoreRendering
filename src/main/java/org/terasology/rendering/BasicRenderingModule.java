/*
 * Copyright 2017 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering;

import javafx.util.Pair;
import org.terasology.context.Context;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.registry.In;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.dag.RenderGraph;

import org.terasology.rendering.dag.gsoc.NewNode;
import org.terasology.rendering.dag.nodes.*;
import org.terasology.rendering.opengl.FBO;
import org.terasology.rendering.opengl.FboConfig;
import org.terasology.rendering.opengl.SwappableFBO;
import org.terasology.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.rendering.opengl.fbms.ImmutableFbo;
import org.terasology.rendering.opengl.fbms.ShadowMapResolutionDependentFbo;
import org.terasology.rendering.world.WorldRenderer;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static org.terasology.rendering.dag.nodes.DownSamplerForExposureNode.FBO_16X16_CONFIG;
import static org.terasology.rendering.dag.nodes.DownSamplerForExposureNode.FBO_1X1_CONFIG;
import static org.terasology.rendering.dag.nodes.DownSamplerForExposureNode.FBO_2X2_CONFIG;
import static org.terasology.rendering.dag.nodes.DownSamplerForExposureNode.FBO_4X4_CONFIG;
import static org.terasology.rendering.dag.nodes.DownSamplerForExposureNode.FBO_8X8_CONFIG;
import static org.terasology.rendering.dag.nodes.LateBlurNode.FIRST_LATE_BLUR_FBO_URI;
import static org.terasology.rendering.dag.nodes.LateBlurNode.SECOND_LATE_BLUR_FBO_URI;
import static org.terasology.rendering.opengl.ScalingFactors.FULL_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.HALF_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.ONE_16TH_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.ONE_32TH_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.ONE_8TH_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.QUARTER_SCALE;

@RegisterSystem
public class BasicRenderingModule extends BaseComponentSystem {

    @In
    protected Context context;
    protected RenderGraph renderGraph;
    protected WorldRenderer worldRendererImpl;

    private DisplayResolutionDependentFbo displayResolutionDependentFbo;
    private ShadowMapResolutionDependentFbo shadowMapResolutionDependentFbo;
    private ImmutableFbo immutableFbo;

    private ShadowMapNode shadowMapNode;

    @Override
    public void initialise() {
        super.initialise();
        context.put(BasicRenderingModule.class,this);
        this.worldRendererImpl = context.get(WorldRenderer.class);
        this.renderGraph = context.get(RenderGraph.class);
        initBasicRendering();
    }

    private void initBasicRendering() {
        immutableFbo = new ImmutableFbo();
        context.put(ImmutableFbo.class, immutableFbo);

        shadowMapResolutionDependentFbo = new ShadowMapResolutionDependentFbo();
        context.put(ShadowMapResolutionDependentFbo.class, shadowMapResolutionDependentFbo);

        displayResolutionDependentFbo = context.get(DisplayResolutionDependentFbo.class);

        addGBufferClearingNodes(renderGraph);

        addSkyNodes(renderGraph);

        addWorldRenderingNodes(renderGraph);

        addLightingNodes(renderGraph);

        add3dDecorationNodes(renderGraph);

        addReflectionAndRefractionNodes(renderGraph);

        addPrePostProcessingNodes(renderGraph);

        addBloomNodes(renderGraph);

        addExposureNodes(renderGraph);

        addInitialPostProcessingNodes(renderGraph);

        addFinalPostProcessingNodes(renderGraph);

        addOutputNodes(renderGraph);

        worldRendererImpl.requestTaskListRefresh();
    }

    private void addGBufferClearingNodes(RenderGraph renderGraph) {
        SwappableFBO gBufferPair = displayResolutionDependentFbo.getGBufferPair();

        BufferClearingNode lastUpdatedGBufferClearingNode = new BufferClearingNode("lastUpdatedGBufferClearingNode", context, gBufferPair.getLastUpdatedFbo(),
                GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        renderGraph.addNode(lastUpdatedGBufferClearingNode);

        BufferClearingNode staleGBufferClearingNode = new BufferClearingNode("staleGBufferClearingNode", context, gBufferPair.getStaleFbo(),
                GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        renderGraph.addNode(staleGBufferClearingNode);
    }

    private void addSkyNodes(RenderGraph renderGraph) {
        NewNode backdropNode = new BackdropNode("backdropNode", context);
        renderGraph.addNode(backdropNode);

        FboConfig intermediateHazeConfig = new FboConfig(HazeNode.INTERMEDIATE_HAZE_FBO_URI, ONE_16TH_SCALE, FBO.Type.DEFAULT);
        FBO intermediateHazeFbo = displayResolutionDependentFbo.request(intermediateHazeConfig);

        HazeNode intermediateHazeNode = new HazeNode("intermediateHazeNode", context,
                                                    intermediateHazeFbo);
        // TODO I introduce new BufferPairConnection but I have to fetch it from the old system. This must be removed when every node uses new system
        // make this implicit
        // intermediateHazeNode.addInputBufferPairConnection(1, new Pair<FBO,FBO>(displayResolutionDependentFbo.getGBufferPair().getLastUpdatedFbo(),
        //                                                                          displayResolutionDependentFbo.getGBufferPair().getStaleFbo()));
        intermediateHazeNode.addInputFboConnection(1, displayResolutionDependentFbo.getGBufferPair().getLastUpdatedFbo());
        renderGraph.addNode(intermediateHazeNode);

        FboConfig finalHazeConfig = new FboConfig(HazeNode.FINAL_HAZE_FBO_URI, ONE_32TH_SCALE, FBO.Type.DEFAULT);
        FBO finalHazeFbo = displayResolutionDependentFbo.request(finalHazeConfig);

        HazeNode finalHazeNode = new HazeNode("finalHazeNode", context, finalHazeFbo);
        renderGraph.connectFbo(intermediateHazeNode, 1, finalHazeNode, 1);
        renderGraph.addNode(finalHazeNode);

        NewNode lastUpdatedGBufferClearingNode = renderGraph.findNode("BasicRendering:lastUpdatedGBufferClearingNode");
        renderGraph.connect(lastUpdatedGBufferClearingNode, backdropNode);
    }

    private void addWorldRenderingNodes(RenderGraph renderGraph) {
        /* Ideally, world rendering nodes only depend on the gBufferClearingNode. However,
        since the haze is produced by blurring the content of the gBuffer and we only want
        the sky color to contribute  to the haze, the world rendering nodes need to run
        after finalHazeNode, so that the landscape and other meshes are not part of the haze.

        Strictly speaking however, it is only the hazeIntermediateNode that should be processed
        before the world rendering nodes. Here we have chosen to also ensure that finalHazeNode is
        processed before the world rendering nodes - not because it's necessary, but to keep all
        the haze-related nodes together. */
        NewNode finalHazeNode = renderGraph.findNode("BasicRendering:finalHazeNode");

        NewNode opaqueObjectsNode = new OpaqueObjectsNode("opaqueObjectsNode", context);

        renderGraph.addNode(opaqueObjectsNode);
        renderGraph.connect(finalHazeNode, opaqueObjectsNode);

        NewNode opaqueBlocksNode = new OpaqueBlocksNode("opaqueBlocksNode", context);
        renderGraph.addNode(opaqueBlocksNode);
        renderGraph.connect(finalHazeNode, opaqueBlocksNode);

        NewNode alphaRejectBlocksNode = new AlphaRejectBlocksNode("alphaRejectBlocksNode", context);
        renderGraph.addNode(alphaRejectBlocksNode);
        renderGraph.connect(finalHazeNode, alphaRejectBlocksNode);

        NewNode overlaysNode = new OverlaysNode("overlaysNode", context);
        renderGraph.addNode(overlaysNode);
        renderGraph.connect(finalHazeNode, overlaysNode);
    }

    private void addLightingNodes(RenderGraph renderGraph) {
        NewNode opaqueObjectsNode = renderGraph.findNode("BasicRendering:opaqueObjectsNode");
        NewNode opaqueBlocksNode = renderGraph.findNode("BasicRendering:opaqueBlocksNode");
        NewNode alphaRejectBlocksNode = renderGraph.findNode("BasicRendering:alphaRejectBlocksNode");
        NewNode lastUpdatedGBufferClearingNode = renderGraph.findNode("BasicRendering:lastUpdatedGBufferClearingNode");
        NewNode staleGBufferClearingNode = renderGraph.findNode("BasicRendering:staleGBufferClearingNode");

        FboConfig shadowMapConfig = new FboConfig(ShadowMapNode.SHADOW_MAP_FBO_URI, FBO.Type.NO_COLOR).useDepthBuffer();
        BufferClearingNode shadowMapClearingNode = new BufferClearingNode("shadowMapClearingNode", context,
                shadowMapConfig, shadowMapResolutionDependentFbo, GL_DEPTH_BUFFER_BIT);
        renderGraph.addNode(shadowMapClearingNode);

        shadowMapNode = new ShadowMapNode("shadowMapNode", context);
        renderGraph.connectFbo(shadowMapClearingNode, 1, shadowMapNode, 1);
        renderGraph.addNode(shadowMapNode);

        NewNode deferredPointLightsNode = new DeferredPointLightsNode("deferredPointLightsNode", context);
        renderGraph.addNode(deferredPointLightsNode);
        renderGraph.connect(opaqueObjectsNode, deferredPointLightsNode);
        renderGraph.connect(opaqueBlocksNode, deferredPointLightsNode);
        renderGraph.connect(alphaRejectBlocksNode, deferredPointLightsNode);

        NewNode deferredMainLightNode = new DeferredMainLightNode("deferredMainLightNode", context);
        renderGraph.connectFbo(shadowMapNode, 1, deferredMainLightNode, 1);
        renderGraph.addNode(deferredMainLightNode);
        renderGraph.connect(opaqueObjectsNode, deferredMainLightNode);
        renderGraph.connect(opaqueBlocksNode, deferredMainLightNode);
        renderGraph.connect(alphaRejectBlocksNode, deferredMainLightNode);
        renderGraph.connect(deferredPointLightsNode, deferredMainLightNode);

        NewNode applyDeferredLightingNode = new ApplyDeferredLightingNode("applyDeferredLightingNode", context);
        renderGraph.addNode(applyDeferredLightingNode);
        renderGraph.connect(deferredMainLightNode, applyDeferredLightingNode);
        renderGraph.connect(deferredPointLightsNode, applyDeferredLightingNode);
        renderGraph.connect(lastUpdatedGBufferClearingNode, applyDeferredLightingNode);
        renderGraph.connect(staleGBufferClearingNode, applyDeferredLightingNode);
    }

    private void add3dDecorationNodes(RenderGraph renderGraph) {
        NewNode opaqueObjectsNode = renderGraph.findNode("BasicRendering:opaqueObjectsNode");
        NewNode opaqueBlocksNode = renderGraph.findNode("BasicRendering:opaqueBlocksNode");
        NewNode alphaRejectBlocksNode = renderGraph.findNode("BasicRendering:alphaRejectBlocksNode");
        NewNode applyDeferredLightingNode = renderGraph.findNode("BasicRendering:applyDeferredLightingNode");

        NewNode outlineNode = new OutlineNode("outlineNode", context);
        renderGraph.addNode(outlineNode);
        renderGraph.connect(opaqueObjectsNode, outlineNode);
        renderGraph.connect(opaqueBlocksNode, outlineNode);
        renderGraph.connect(alphaRejectBlocksNode, outlineNode);

        NewNode ambientOcclusionNode = new AmbientOcclusionNode("ambientOcclusionNode", context);
        renderGraph.addNode(ambientOcclusionNode);
        renderGraph.connect(opaqueObjectsNode, ambientOcclusionNode);
        renderGraph.connect(opaqueBlocksNode, ambientOcclusionNode);
        renderGraph.connect(alphaRejectBlocksNode, ambientOcclusionNode);
        // TODO: At this stage, it is unclear -why- this connection is required, we just know that it's required. Investigate.
        renderGraph.connect(applyDeferredLightingNode, ambientOcclusionNode);

        NewNode blurredAmbientOcclusionNode = new BlurredAmbientOcclusionNode("blurredAmbientOcclusionNode", context);
        renderGraph.connectFbo(ambientOcclusionNode, 1, blurredAmbientOcclusionNode,1);
        renderGraph.addNode(blurredAmbientOcclusionNode);
        renderGraph.connect(ambientOcclusionNode, blurredAmbientOcclusionNode);
    }

    private void addReflectionAndRefractionNodes(RenderGraph renderGraph) {
        FboConfig reflectedBufferConfig = new FboConfig(BackdropReflectionNode.REFLECTED_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT).useDepthBuffer();
        BufferClearingNode reflectedBufferClearingNode = new BufferClearingNode("reflectedBufferClearingNode", context, reflectedBufferConfig,
                displayResolutionDependentFbo, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderGraph.addNode(reflectedBufferClearingNode);

        NewNode reflectedBackdropNode = new BackdropReflectionNode("reflectedBackdropNode", context);
        renderGraph.connectFbo(reflectedBufferClearingNode, 1, reflectedBackdropNode, 1);
        renderGraph.addNode(reflectedBackdropNode);

        NewNode worldReflectionNode = new WorldReflectionNode("worldReflectionNode", context);
        renderGraph.connectFbo(reflectedBackdropNode, 1, worldReflectionNode, 1);
        renderGraph.addNode(worldReflectionNode);

        FboConfig reflectedRefractedBufferConfig = new FboConfig(RefractiveReflectiveBlocksNode.REFRACTIVE_REFLECTIVE_FBO_URI, FULL_SCALE, FBO.Type.HDR).useNormalBuffer();
        BufferClearingNode reflectedRefractedBufferClearingNode = new BufferClearingNode("reflectedRefractedBufferClearingNode", context, reflectedRefractedBufferConfig,
                displayResolutionDependentFbo, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderGraph.addNode(reflectedRefractedBufferClearingNode);

        NewNode chunksRefractiveReflectiveNode = new RefractiveReflectiveBlocksNode("chunksRefractiveReflectiveNode", context);
        renderGraph.connectFbo(reflectedRefractedBufferClearingNode, 1, chunksRefractiveReflectiveNode, 1);
        renderGraph.connectFbo(worldReflectionNode, 1, chunksRefractiveReflectiveNode, 2);
        renderGraph.addNode(chunksRefractiveReflectiveNode);

        NewNode applyDeferredLightingNode = renderGraph.findNode("BasicRendering:applyDeferredLightingNode");
        // renderGraph.connect(reflectedRefractedBufferClearingNode, chunksRefractiveReflectiveNode);
        // renderGraph.connect(worldReflectionNode, chunksRefractiveReflectiveNode);
        // TODO: At this stage, it is unclear -why- this connection is required, we just know that it's required. Investigate.
        renderGraph.connect(applyDeferredLightingNode, chunksRefractiveReflectiveNode);
        // TODO: consider having a non-rendering node for FBO.attachDepthBufferTo() methods
    }

    private void addPrePostProcessingNodes(RenderGraph renderGraph) {
        // Pre-post-processing, just one more interaction with 3D data (semi-transparent objects, in SimpleBlendMaterialsNode)
        // and then it's 2D post-processing all the way to the image shown on the display.

        NewNode overlaysNode = renderGraph.findNode("BasicRendering:overlaysNode");
        NewNode finalHazeNode = renderGraph.findNode("BasicRendering:finalHazeNode");
        NewNode chunksRefractiveReflectiveNode = renderGraph.findNode("BasicRendering:chunksRefractiveReflectiveNode");
        NewNode applyDeferredLightingNode = renderGraph.findNode("BasicRendering:applyDeferredLightingNode");
        NewNode outlineNode = renderGraph.findNode("BasicRendering:outlineNode");
        NewNode blurredAmbientOcclusionNode = renderGraph.findNode("BasicRendering:blurredAmbientOcclusionNode");

        NewNode prePostCompositeNode = new PrePostCompositeNode("prePostCompositeNode", context);
        renderGraph.connectFbo(blurredAmbientOcclusionNode, 1, prePostCompositeNode,1);
        renderGraph.connectFbo(outlineNode, 1, prePostCompositeNode, 2);
        renderGraph.connectFbo(finalHazeNode, 1, prePostCompositeNode, 3);
        renderGraph.connectFbo(chunksRefractiveReflectiveNode, 1, prePostCompositeNode, 4);
        renderGraph.addNode(prePostCompositeNode);
        renderGraph.connect(overlaysNode, prePostCompositeNode);
        // renderGraph.connect(finalHazeNode, prePostCompositeNode);
        // renderGraph.connect(chunksRefractiveReflectiveNode, prePostCompositeNode);
        renderGraph.connect(applyDeferredLightingNode, prePostCompositeNode);
        // renderGraph.connect(outlineNode, prePostCompositeNode);
        // renderGraph.connect(blurredAmbientOcclusionNode, prePostCompositeNode);

        NewNode simpleBlendMaterialsNode = new SimpleBlendMaterialsNode("simpleBlendMaterialsNode", context);
        renderGraph.addNode(simpleBlendMaterialsNode);
        renderGraph.connect(prePostCompositeNode, simpleBlendMaterialsNode);
    }

    private void addBloomNodes(RenderGraph renderGraph) {
        // Bloom Effect: one high-pass filter and three blur passes

        NewNode highPassNode = new HighPassNode("highPassNode", context);
        renderGraph.addNode(highPassNode);

        FboConfig halfScaleBloomConfig = new FboConfig(BloomBlurNode.HALF_SCALE_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT);
        FBO halfScaleBloomFbo = displayResolutionDependentFbo.request(halfScaleBloomConfig);

        // TODO once everything is new system based, update halfscaleblurrednode's input obtaining
        BloomBlurNode halfScaleBlurredBloomNode = new BloomBlurNode("halfScaleBlurredBloomNode", context, halfScaleBloomFbo);
        // halfScaleBlurredBloomNode.addInputFboConnection(1, displayResolutionDependentFbo.get(HighPassNode.HIGH_PASS_FBO_URI));
        renderGraph.connectFbo(highPassNode, 1, halfScaleBlurredBloomNode, 1);
        renderGraph.addNode(halfScaleBlurredBloomNode);

        FboConfig quarterScaleBloomConfig = new FboConfig(BloomBlurNode.QUARTER_SCALE_FBO_URI, QUARTER_SCALE, FBO.Type.DEFAULT);
        FBO quarterScaleBloomFbo = displayResolutionDependentFbo.request(quarterScaleBloomConfig);

        BloomBlurNode quarterScaleBlurredBloomNode = new BloomBlurNode("quarterScaleBlurredBloomNode", context, quarterScaleBloomFbo);
        renderGraph.connectFbo(halfScaleBlurredBloomNode, 1, quarterScaleBlurredBloomNode, 1);
        renderGraph.addNode(quarterScaleBlurredBloomNode);

        FboConfig one8thScaleBloomConfig = new FboConfig(BloomBlurNode.ONE_8TH_SCALE_FBO_URI, ONE_8TH_SCALE, FBO.Type.DEFAULT);
        FBO one8thScaleBloomFbo = displayResolutionDependentFbo.request(one8thScaleBloomConfig);

        BloomBlurNode one8thScaleBlurredBloomNode = new BloomBlurNode("one8thScaleBlurredBloomNode", context, one8thScaleBloomFbo);
        renderGraph.connectFbo(quarterScaleBlurredBloomNode, 1, one8thScaleBlurredBloomNode, 1);
        renderGraph.addNode(one8thScaleBlurredBloomNode);

        NewNode simpleBlendMaterialsNode = renderGraph.findNode("BasicRendering:simpleBlendMaterialsNode");
        renderGraph.connect(simpleBlendMaterialsNode, highPassNode);
    }

    private void addExposureNodes(RenderGraph renderGraph) {
        SimpleBlendMaterialsNode simpleBlendMaterialsNode = (SimpleBlendMaterialsNode) renderGraph.findNode("BasicRendering:simpleBlendMaterialsNode");
        // FboConfig gBuffer2Config = displayResolutionDependentFbo.getFboConfig(new SimpleUri("BasicRendering:fbo.gBuffer2")); // TODO: Remove the hard coded value here
        DownSamplerForExposureNode exposureDownSamplerTo16pixels = new DownSamplerForExposureNode("exposureDownSamplerTo16pixels",
                context, displayResolutionDependentFbo, FBO_16X16_CONFIG, immutableFbo);
        renderGraph.connectFbo(simpleBlendMaterialsNode, 1, exposureDownSamplerTo16pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo16pixels);

        DownSamplerForExposureNode exposureDownSamplerTo8pixels = new DownSamplerForExposureNode("exposureDownSamplerTo8pixels", context,
                immutableFbo, FBO_8X8_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo16pixels, 1, exposureDownSamplerTo8pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo8pixels);


        DownSamplerForExposureNode exposureDownSamplerTo4pixels = new DownSamplerForExposureNode("exposureDownSamplerTo4pixels", context,
                immutableFbo, FBO_4X4_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo8pixels, 1, exposureDownSamplerTo4pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo4pixels);

        DownSamplerForExposureNode exposureDownSamplerTo2pixels = new DownSamplerForExposureNode("exposureDownSamplerTo2pixels", context,
                immutableFbo, FBO_2X2_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo4pixels, 1, exposureDownSamplerTo2pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo2pixels);

        DownSamplerForExposureNode exposureDownSamplerTo1pixel = new DownSamplerForExposureNode("exposureDownSamplerTo1pixel", context,
                immutableFbo, FBO_1X1_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo2pixels, 1, exposureDownSamplerTo1pixel, 1);
        renderGraph.addNode(exposureDownSamplerTo1pixel);

        NewNode updateExposureNode = new UpdateExposureNode("updateExposureNode", context);
        renderGraph.connectFbo(exposureDownSamplerTo1pixel, 1, updateExposureNode, 1);
        renderGraph.addNode(updateExposureNode);

        // renderGraph.connect(simpleBlendMaterialsNode, exposureDownSamplerTo16pixels, exposureDownSamplerTo8pixels,
        //        exposureDownSamplerTo4pixels, exposureDownSamplerTo2pixels, exposureDownSamplerTo1pixel,
        //        updateExposureNode);
    }

    private void addInitialPostProcessingNodes(RenderGraph renderGraph) {
        NewNode simpleBlendMaterialsNode = renderGraph.findNode("BasicRendering:simpleBlendMaterialsNode");
        NewNode one8thScaleBlurredBloomNode = renderGraph.findNode("BasicRendering:one8thScaleBlurredBloomNode");

        // Light shafts
        LightShaftsNode lightShaftsNode = new LightShaftsNode("lightShaftsNode", context);

        renderGraph.addNode(lightShaftsNode);
        renderGraph.connect(simpleBlendMaterialsNode, lightShaftsNode);

        // Adding the bloom and light shafts to the gBuffer
        NewNode initialPostProcessingNode = new InitialPostProcessingNode("initialPostProcessingNode", context);
        renderGraph.addNode(initialPostProcessingNode);
        renderGraph.connect(lightShaftsNode, initialPostProcessingNode);
        renderGraph.connect(one8thScaleBlurredBloomNode, initialPostProcessingNode);
    }

    private void addFinalPostProcessingNodes(RenderGraph renderGraph) {
        NewNode initialPostProcessingNode = renderGraph.findNode("BasicRendering:initialPostProcessingNode");
        NewNode updateExposureNode = renderGraph.findNode("BasicRendering:updateExposureNode");

        ToneMappingNode toneMappingNode = new ToneMappingNode("toneMappingNode", context);

        renderGraph.addNode(toneMappingNode);
        renderGraph.connect(updateExposureNode, toneMappingNode);
        renderGraph.connect(initialPostProcessingNode, toneMappingNode);

        // Late Blur nodes: assisting Motion Blur and Depth-of-Field effects
        FboConfig firstLateBlurConfig = new FboConfig(FIRST_LATE_BLUR_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT);
        FBO firstLateBlurFbo = displayResolutionDependentFbo.request(firstLateBlurConfig);

        LateBlurNode firstLateBlurNode = new LateBlurNode("firstLateBlurNode", context, firstLateBlurFbo);
        renderGraph.connectFbo(toneMappingNode, 1, firstLateBlurNode, 1);
        renderGraph.addNode(firstLateBlurNode);

        FboConfig secondLateBlurConfig = new FboConfig(SECOND_LATE_BLUR_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT);
        FBO secondLateBlurFbo = displayResolutionDependentFbo.request(secondLateBlurConfig);

        LateBlurNode secondLateBlurNode = new LateBlurNode("secondLateBlurNode", context, secondLateBlurFbo);
        renderGraph.connectFbo(firstLateBlurNode, 1, secondLateBlurNode, 1);
        renderGraph.addNode(secondLateBlurNode);

        FinalPostProcessingNode finalPostProcessingNode = new FinalPostProcessingNode("finalPostProcessingNode", context/*finalIn1*/);
        renderGraph.connectFbo(toneMappingNode,1, finalPostProcessingNode, 1);
        renderGraph.connectFbo(secondLateBlurNode, 1, finalPostProcessingNode,2);

        renderGraph.addNode(finalPostProcessingNode);

        // renderGraph.connect(toneMappingNode, firstLateBlurNode, secondLateBlurNode);
    }

    private void addOutputNodes(RenderGraph renderGraph) {
        NewNode finalPostProcessingNode = renderGraph.findNode("BasicRendering:finalPostProcessingNode");

//        NewNode  tintNode = new TintNode("tintNode", context);
//        tintNode.connectFbo(1, finalPostProcessingNode.getOutputFboConnection(1));
//        renderGraph.addNode(tintNode);

        NewNode outputToVRFrameBufferNode = new OutputToHMDNode("outputToVRFrameBufferNode", context);
        renderGraph.addNode(outputToVRFrameBufferNode);

        // renderGraph.connect(finalPostProcessingNode, outputToVRFrameBufferNode);

        NewNode outputToScreenNode = new OutputToScreenNode("outputToScreenNode", context);
        renderGraph.connectBufferPair(finalPostProcessingNode, 1, outputToScreenNode, 1);
        renderGraph.connectFbo(finalPostProcessingNode, 1, outputToScreenNode, 1);
        renderGraph.addNode(outputToScreenNode);
        // renderGraph.connect(finalPostProcessingNode, outputToScreenNode);
        // renderGraph.connectFbo(finalPostProcessingNode, tintNode, outputToScreenNode);
    }

    public Camera getLightCamera() {
        //FIXME: remove this method
        return shadowMapNode.shadowMapCamera;
    }


}
