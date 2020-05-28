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
package org.terasology.corerendering.rendering;

import org.lwjgl.opengl.Display;
import org.terasology.corerendering.rendering.dag.nodes.*;
import org.terasology.context.Context;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.dag.Node;
import org.terasology.rendering.dag.RenderGraph;

import org.terasology.rendering.dag.AbstractNode;
import org.terasology.rendering.dag.dependencyConnections.BufferPair;
import org.terasology.rendering.dag.ModuleRendering;
import org.terasology.rendering.opengl.FBO;
import org.terasology.rendering.opengl.FboConfig;
import org.terasology.rendering.opengl.SwappableFBO;
import org.terasology.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.rendering.opengl.fbms.ImmutableFbo;
import org.terasology.rendering.opengl.fbms.ShadowMapResolutionDependentFbo;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT;
import static org.terasology.corerendering.rendering.dag.nodes.DownSamplerForExposureNode.FBO_16X16_CONFIG;
import static org.terasology.corerendering.rendering.dag.nodes.DownSamplerForExposureNode.FBO_1X1_CONFIG;
import static org.terasology.corerendering.rendering.dag.nodes.DownSamplerForExposureNode.FBO_2X2_CONFIG;
import static org.terasology.corerendering.rendering.dag.nodes.DownSamplerForExposureNode.FBO_4X4_CONFIG;
import static org.terasology.corerendering.rendering.dag.nodes.DownSamplerForExposureNode.FBO_8X8_CONFIG;
import static org.terasology.corerendering.rendering.dag.nodes.LateBlurNode.FIRST_LATE_BLUR_FBO_URI;
import static org.terasology.corerendering.rendering.dag.nodes.LateBlurNode.SECOND_LATE_BLUR_FBO_URI;
import static org.terasology.rendering.opengl.ScalingFactors.FULL_SCALE;
import static org.terasology.rendering.opengl.ScalingFactors.HALF_SCALE;

public class CoreRenderingModule extends ModuleRendering {

    private DisplayResolutionDependentFbo displayResolutionDependentFbo;
    private ShadowMapResolutionDependentFbo shadowMapResolutionDependentFbo;
    private ImmutableFbo immutableFbo;

    private ShadowMapNode shadowMapNode;

    private static int initializationPriority = 1;

    // Created in renderingModuleRegistry trough reflection and Constructor calling
    public CoreRenderingModule(Context context) {
        super(context);
        setInitializationPriority(initializationPriority);
    }

    @Override
    public void initialise() {
        super.initialise();

        initCoreRendering();
    }

    private void initCoreRendering() {
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
    }

    private void addGBufferClearingNodes(RenderGraph renderGraph) {
        // SwappableFBO gBufferPair = displayResolutionDependentFbo.getGBufferPair();
        // TODO leave this here?
        FBO.Dimensions fullScale = new FBO.Dimensions();
        fullScale.setDimensions(Display.getWidth(), Display.getHeight());

        SwappableFBO legacyGBuffers = displayResolutionDependentFbo.getGBufferPair();

        // BufferPair gBufferPair = createBufferPair("gBuffer1", "gBuffer2",
        // FULL_SCALE, FBO.Type.HDR, fullScale);
        BufferPair gBufferPair = new BufferPair(legacyGBuffers.getLastUpdatedFbo(), legacyGBuffers.getStaleFbo());

        BufferClearingNode lastUpdatedGBufferClearingNode = new BufferClearingNode("lastUpdatedGBufferClearingNode", context, providingModule,
                GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        lastUpdatedGBufferClearingNode.addInputFboConnection(1, gBufferPair.getPrimaryFbo());
        lastUpdatedGBufferClearingNode.addOutputBufferPairConnection(1, gBufferPair);

        renderGraph.addNode(lastUpdatedGBufferClearingNode);

        BufferClearingNode staleGBufferClearingNode = new BufferClearingNode("staleGBufferClearingNode", context, providingModule,
                GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
        staleGBufferClearingNode.addInputFboConnection(1, gBufferPair.getSecondaryFbo());
        staleGBufferClearingNode.addOutputBufferPairConnection(1, gBufferPair);
        renderGraph.addNode(staleGBufferClearingNode);
    }

    private void addSkyNodes(RenderGraph renderGraph) {
        Node lastUpdatedGBufferClearingNode = renderGraph.findNode("CoreRendering:lastUpdatedGBufferClearingNode");
        // TODO maybe read from both clearing nodes and output bufferpair then, created along the way,
        //  don't make them output buffer pair when they only clear 1 of the buffers. Prone to error
        Node backdropNode = new BackdropNode("backdropNode", providingModule, context);
        renderGraph.connectBufferPair(lastUpdatedGBufferClearingNode, 1, backdropNode, 1);
        ((AbstractNode) backdropNode).addOutputBufferPairConnection(1, lastUpdatedGBufferClearingNode.getOutputBufferPairConnection(1).getBufferPair());

        renderGraph.addNode(backdropNode);
        //renderGraph.connect(lastUpdatedGBufferClearingNode, backdropNode);
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
        Node backdropNode = renderGraph.findNode("CoreRendering:backdropNode");

        Node opaqueObjectsNode = new OpaqueObjectsNode("opaqueObjectsNode", providingModule, context);
        renderGraph.connectBufferPair(backdropNode, 1, opaqueObjectsNode, 1);
        renderGraph.addNode(opaqueObjectsNode);
        // renderGraph.connect(finalHazeNode, opaqueObjectsNode);

        Node opaqueBlocksNode = new OpaqueBlocksNode("opaqueBlocksNode", providingModule, context);
        renderGraph.connectBufferPair(backdropNode, 1, opaqueBlocksNode, 1);
        renderGraph.addNode(opaqueBlocksNode);
        // renderGraph.connect(finalHazeNode, opaqueBlocksNode);

        Node alphaRejectBlocksNode = new AlphaRejectBlocksNode("alphaRejectBlocksNode", providingModule, context);
        renderGraph.connectBufferPair(backdropNode, 1, alphaRejectBlocksNode, 1);
        renderGraph.addNode(alphaRejectBlocksNode);
        // renderGraph.connect(finalHazeNode, alphaRejectBlocksNode);

        Node overlaysNode = new OverlaysNode("overlaysNode", providingModule, context);
        renderGraph.connectBufferPair(backdropNode, 1, overlaysNode, 1);
        renderGraph.addNode(overlaysNode);
        // renderGraph.connect(finalHazeNode, overlaysNode);
    }

    private void addLightingNodes(RenderGraph renderGraph) {
        Node opaqueObjectsNode = renderGraph.findNode("CoreRendering:opaqueObjectsNode");
        Node opaqueBlocksNode = renderGraph.findNode("CoreRendering:opaqueBlocksNode");
        Node alphaRejectBlocksNode = renderGraph.findNode("CoreRendering:alphaRejectBlocksNode");

        Node deferredPointLightsNode = new DeferredPointLightsNode("deferredPointLightsNode", providingModule, context);
        renderGraph.connectBufferPair(opaqueObjectsNode, 1, deferredPointLightsNode, 1);
        renderGraph.addNode(deferredPointLightsNode);
        renderGraph.connectRunOrder(opaqueBlocksNode, 1, deferredPointLightsNode, 1);
        renderGraph.connectRunOrder(alphaRejectBlocksNode, 1, deferredPointLightsNode, 2);

        Node deferredMainLightNode = new DeferredMainLightNode("deferredMainLightNode", providingModule, context);
        // renderGraph.connectFbo(shadowMapNode, 1, deferredMainLightNode, 1);
        renderGraph.connectBufferPair(opaqueBlocksNode, 1, deferredMainLightNode, 1);
        renderGraph.addNode(deferredMainLightNode);
        renderGraph.connectRunOrder(opaqueObjectsNode, 1, deferredMainLightNode, 1);
        renderGraph.connectRunOrder(alphaRejectBlocksNode, 2, deferredMainLightNode, 2);
        renderGraph.connectRunOrder(deferredPointLightsNode, 1, deferredMainLightNode, 3);

        Node applyDeferredLightingNode = new ApplyDeferredLightingNode("applyDeferredLightingNode", providingModule, context);
        renderGraph.connectBufferPair(deferredMainLightNode, 1, applyDeferredLightingNode, 1);
        renderGraph.addNode(applyDeferredLightingNode);
        renderGraph.connectRunOrder(deferredPointLightsNode, 2, applyDeferredLightingNode, 1);
    }

    private void add3dDecorationNodes(RenderGraph renderGraph) {
        Node applyDeferredLightingNode = renderGraph.findNode("CoreRendering:applyDeferredLightingNode");

        Node outlineNode = new OutlineNode("outlineNode", providingModule, context);
        renderGraph.connectBufferPair(applyDeferredLightingNode, 1, outlineNode, 1);
        renderGraph.addNode(outlineNode);
    }

    private void addReflectionAndRefractionNodes(RenderGraph renderGraph) {
        Node applyDeferredLightingNode = renderGraph.findNode("CoreRendering:applyDeferredLightingNode");

        FboConfig reflectedBufferConfig = new FboConfig(BackdropReflectionNode.REFLECTED_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT).useDepthBuffer();
        BufferClearingNode reflectedBufferClearingNode = new BufferClearingNode("reflectedBufferClearingNode", providingModule, context, reflectedBufferConfig,
                displayResolutionDependentFbo, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderGraph.addNode(reflectedBufferClearingNode);

        Node reflectedBackdropNode = new BackdropReflectionNode("reflectedBackdropNode", providingModule, context);
        renderGraph.connectFbo(reflectedBufferClearingNode, 1, reflectedBackdropNode, 1);
        renderGraph.addNode(reflectedBackdropNode);

        Node worldReflectionNode = new WorldReflectionNode("worldReflectionNode", providingModule, context);
        renderGraph.connectFbo(reflectedBackdropNode, 1, worldReflectionNode, 1);
        renderGraph.addNode(worldReflectionNode);

        FboConfig reflectedRefractedBufferConfig = new FboConfig(RefractiveReflectiveBlocksNode.REFRACTIVE_REFLECTIVE_FBO_URI, FULL_SCALE, FBO.Type.HDR).useNormalBuffer();
        BufferClearingNode reflectedRefractedBufferClearingNode = new BufferClearingNode("reflectedRefractedBufferClearingNode", providingModule,
                context, reflectedRefractedBufferConfig, displayResolutionDependentFbo, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderGraph.addNode(reflectedRefractedBufferClearingNode);

        Node chunksRefractiveReflectiveNode = new RefractiveReflectiveBlocksNode("chunksRefractiveReflectiveNode", providingModule, context);
        renderGraph.connectBufferPair(applyDeferredLightingNode, 1, chunksRefractiveReflectiveNode, 1);
        renderGraph.connectFbo(reflectedRefractedBufferClearingNode, 1, chunksRefractiveReflectiveNode, 1);
        renderGraph.connectFbo(worldReflectionNode, 1, chunksRefractiveReflectiveNode, 2);
        renderGraph.addNode(chunksRefractiveReflectiveNode);

        // renderGraph.connect(reflectedRefractedBufferClearingNode, chunksRefractiveReflectiveNode);
        // renderGraph.connect(worldReflectionNode, chunksRefractiveReflectiveNode);
        // TODO: At this stage, it is unclear -why- this connection is required, we just know that it's required. Investigate.
        // renderGraph.connect(applyDeferredLightingNode, chunksRefractiveReflectiveNode);
        // TODO: consider having a non-rendering node for FBO.attachDepthBufferTo() methods
    }

    private void addPrePostProcessingNodes(RenderGraph renderGraph) {
        // Pre-post-processing, just one more interaction with 3D data (semi-transparent objects, in SimpleBlendMaterialsNode)
        // and then it's 2D post-processing all the way to the image shown on the display.

        Node overlaysNode = renderGraph.findNode("CoreRendering:overlaysNode");
        Node finalHazeNode = renderGraph.findNode("CoreRendering:finalHazeNode");
        Node chunksRefractiveReflectiveNode = renderGraph.findNode("CoreRendering:chunksRefractiveReflectiveNode");
        Node applyDeferredLightingNode = renderGraph.findNode("CoreRendering:applyDeferredLightingNode");
        Node outlineNode = renderGraph.findNode("CoreRendering:outlineNode");
        // Node blurredAmbientOcclusionNode = renderGraph.findAka("blurredAmbientOcclusion");

        Node prePostCompositeNode = new PrePostCompositeNode("prePostCompositeNode", providingModule, context);
        // swapped bufferPairInstance
        renderGraph.connectBufferPair(applyDeferredLightingNode, 1, prePostCompositeNode, 1);
        // renderGraph.connectFbo(blurredAmbientOcclusionNode, 1, prePostCompositeNode,1);
        renderGraph.connectFbo(outlineNode, 1, prePostCompositeNode, 2);
        // renderGraph.connectFbo(finalHazeNode, 1, prePostCompositeNode, 3);
        renderGraph.connectFbo(chunksRefractiveReflectiveNode, 1, prePostCompositeNode, 4);
        renderGraph.addNode(prePostCompositeNode);
        renderGraph.connectRunOrder(overlaysNode, 1, prePostCompositeNode, 1);
        // renderGraph.connect(finalHazeNode, prePostCompositeNode);
        // renderGraph.connect(chunksRefractiveReflectiveNode, prePostCompositeNode);
        renderGraph.connectRunOrder(applyDeferredLightingNode, 1, prePostCompositeNode, 2);
        // renderGraph.connect(outlineNode, prePostCompositeNode);
        // renderGraph.connect(blurredAmbientOcclusionNode, prePostCompositeNode);

        Node simpleBlendMaterialsNode = new SimpleBlendMaterialsNode("simpleBlendMaterialsNode", providingModule, context);
        renderGraph.connectBufferPair(prePostCompositeNode, 1, simpleBlendMaterialsNode, 1);
        renderGraph.addNode(simpleBlendMaterialsNode);
        // renderGraph.connect(prePostCompositeNode, simpleBlendMaterialsNode);
    }

    private void addBloomNodes(RenderGraph renderGraph) {

    }

    private void addExposureNodes(RenderGraph renderGraph) {
            SimpleBlendMaterialsNode simpleBlendMaterialsNode = (SimpleBlendMaterialsNode) renderGraph.findNode("CoreRendering:simpleBlendMaterialsNode");
        // FboConfig gBuffer2Config = displayResolutionDependentFbo.getFboConfig(new SimpleUri("CoreRendering:fbo.gBuffer2")); // TODO: Remove the hard coded value here
        DownSamplerForExposureNode exposureDownSamplerTo16pixels = new DownSamplerForExposureNode("exposureDownSamplerTo16pixels", providingModule,
                context, displayResolutionDependentFbo, FBO_16X16_CONFIG, immutableFbo);
        renderGraph.connectFbo(simpleBlendMaterialsNode, 1, exposureDownSamplerTo16pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo16pixels);

        DownSamplerForExposureNode exposureDownSamplerTo8pixels = new DownSamplerForExposureNode("exposureDownSamplerTo8pixels", providingModule, context,
                immutableFbo, FBO_8X8_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo16pixels, 1, exposureDownSamplerTo8pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo8pixels);


        DownSamplerForExposureNode exposureDownSamplerTo4pixels = new DownSamplerForExposureNode("exposureDownSamplerTo4pixels", providingModule, context,
                immutableFbo, FBO_4X4_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo8pixels, 1, exposureDownSamplerTo4pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo4pixels);

        DownSamplerForExposureNode exposureDownSamplerTo2pixels = new DownSamplerForExposureNode("exposureDownSamplerTo2pixels", providingModule, context,
                immutableFbo, FBO_2X2_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo4pixels, 1, exposureDownSamplerTo2pixels, 1);
        renderGraph.addNode(exposureDownSamplerTo2pixels);

        DownSamplerForExposureNode exposureDownSamplerTo1pixel = new DownSamplerForExposureNode("exposureDownSamplerTo1pixel", providingModule, context,
                immutableFbo, FBO_1X1_CONFIG, immutableFbo);
        renderGraph.connectFbo(exposureDownSamplerTo2pixels, 1, exposureDownSamplerTo1pixel, 1);
        renderGraph.addNode(exposureDownSamplerTo1pixel);

        Node updateExposureNode = new UpdateExposureNode("updateExposureNode", providingModule, context);
        renderGraph.connectFbo(exposureDownSamplerTo1pixel, 1, updateExposureNode, 1);
        renderGraph.addNode(updateExposureNode);

        // renderGraph.connect(simpleBlendMaterialsNode, exposureDownSamplerTo16pixels, exposureDownSamplerTo8pixels,
        //        exposureDownSamplerTo4pixels, exposureDownSamplerTo2pixels, exposureDownSamplerTo1pixel,
        //        updateExposureNode);
    }

    private void addInitialPostProcessingNodes(RenderGraph renderGraph) {
        Node simpleBlendMaterialsNode = renderGraph.findNode("CoreRendering:simpleBlendMaterialsNode");
        Node one8thScaleBlurredBloomNode = renderGraph.findNode("CoreRendering:one8thScaleBlurredBloomNode");

        // Adding the bloom and light shafts to the gBuffer
        Node initialPostProcessingNode = new InitialPostProcessingNode("initialPostProcessingNode", providingModule, context);
        renderGraph.connectBufferPair(simpleBlendMaterialsNode, 1, initialPostProcessingNode, 1);
        // renderGraph.connectFbo(lightShaftsNode, 1, initialPostProcessingNode, 1);
        // renderGraph.connectFbo(one8thScaleBlurredBloomNode, 1, initialPostProcessingNode, 2);
        renderGraph.addNode(initialPostProcessingNode);
    }

    private void addFinalPostProcessingNodes(RenderGraph renderGraph) {
        Node initialPostProcessingNode = renderGraph.findNode("CoreRendering:initialPostProcessingNode");
        Node updateExposureNode = renderGraph.findNode("CoreRendering:updateExposureNode");

        ToneMappingNode toneMappingNode = new ToneMappingNode("toneMappingNode", providingModule, context);
        renderGraph.connectFbo(initialPostProcessingNode, 1, toneMappingNode, 1);
        renderGraph.addNode(toneMappingNode);
        renderGraph.connectRunOrder(updateExposureNode, 1, toneMappingNode, 1);

        // Late Blur nodes: assisting Motion Blur and Depth-of-Field effects
        FboConfig firstLateBlurConfig = new FboConfig(FIRST_LATE_BLUR_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT);
        FBO firstLateBlurFbo = displayResolutionDependentFbo.request(firstLateBlurConfig);

        LateBlurNode firstLateBlurNode = new LateBlurNode("firstLateBlurNode", providingModule, context, firstLateBlurFbo);
        renderGraph.connectFbo(toneMappingNode, 1, firstLateBlurNode, 1);
        renderGraph.addNode(firstLateBlurNode);

        FboConfig secondLateBlurConfig = new FboConfig(SECOND_LATE_BLUR_FBO_URI, HALF_SCALE, FBO.Type.DEFAULT);
        FBO secondLateBlurFbo = displayResolutionDependentFbo.request(secondLateBlurConfig);

        LateBlurNode secondLateBlurNode = new LateBlurNode("secondLateBlurNode", providingModule, context, secondLateBlurFbo);
        renderGraph.connectFbo(firstLateBlurNode, 1, secondLateBlurNode, 1);
        renderGraph.addNode(secondLateBlurNode);

        FinalPostProcessingNode finalPostProcessingNode = new FinalPostProcessingNode("finalPostProcessingNode", providingModule, context/*finalIn1*/);
        renderGraph.connectBufferPair(initialPostProcessingNode, 1, finalPostProcessingNode, 1);
        renderGraph.connectFbo(toneMappingNode,1, finalPostProcessingNode, 1);
        renderGraph.connectFbo(secondLateBlurNode, 1, finalPostProcessingNode,2);
        renderGraph.addNode(finalPostProcessingNode);

        // renderGraph.connect(toneMappingNode, firstLateBlurNode, secondLateBlurNode);
    }

    private void addOutputNodes(RenderGraph renderGraph) {
        Node finalPostProcessingNode = renderGraph.findNode("CoreRendering:finalPostProcessingNode");

//        Node  tintNode = new TintNode("tintNode", context);
//        tintNode.connectFbo(1, finalPostProcessingNode.getOutputFboConnection(1));
//        renderGraph.addNode(tintNode);

        Node outputToVRFrameBufferNode = new OutputToHMDNode("outputToVRFrameBufferNode", providingModule, context);
        renderGraph.addNode(outputToVRFrameBufferNode);

        // renderGraph.connect(finalPostProcessingNode, outputToVRFrameBufferNode);

        Node outputToScreenNode = new OutputToScreenNode("outputToScreenNode", providingModule, context);
        renderGraph.connectBufferPair(finalPostProcessingNode, 1, outputToScreenNode, 1);
        renderGraph.connectFbo(finalPostProcessingNode, 1, outputToScreenNode, 1);
        renderGraph.addNode(outputToScreenNode);
        // renderGraph.connect(finalPostProcessingNode, outputToScreenNode);
        // renderGraph.connectFbo(finalPostProcessingNode, tintNode, outputToScreenNode);
    }


    public Camera getLightCamera() {
        // TODO Hack around our shadow node in adv. module. This ain't gonna work without adv.module
        shadowMapNode = (ShadowMapNode) renderGraph.findAka("shadowMap");
        return shadowMapNode.shadowMapCamera;
    }


}
