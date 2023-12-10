// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.corerendering.rendering.dag.nodes;

import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.terasology.engine.config.Config;
import org.terasology.engine.config.RenderingConfig;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.SimpleUri;
import org.terasology.engine.monitoring.PerformanceMonitor;
import org.terasology.engine.rendering.assets.material.Material;
import org.terasology.engine.rendering.assets.mesh.Mesh;
import org.terasology.engine.rendering.assets.texture.Texture;
import org.terasology.engine.rendering.assets.texture.TextureData;
import org.terasology.engine.rendering.cameras.Camera;
import org.terasology.engine.rendering.dag.ConditionDependentNode;
import org.terasology.engine.rendering.dag.dependencyConnections.BufferPairConnection;
import org.terasology.engine.rendering.dag.stateChanges.BindFbo;
import org.terasology.engine.rendering.dag.stateChanges.EnableMaterial;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTexture2D;
import org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo;
import org.terasology.engine.rendering.dag.stateChanges.SetViewportToSizeOf;
import org.terasology.engine.rendering.opengl.FBO;
import org.terasology.engine.rendering.opengl.FboConfig;
import org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo;
import org.terasology.engine.utilities.Assets;
import org.terasology.engine.utilities.random.FastRandom;
import org.terasology.engine.utilities.random.Random;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.naming.Name;
import org.terasology.math.TeraMath;
import org.terasology.nui.properties.Range;

import java.beans.PropertyChangeEvent;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Optional;

import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.DepthStencilTexture;
import static org.terasology.engine.rendering.dag.stateChanges.SetInputTextureFromFbo.FboTexturesTypes.NormalsTexture;
import static org.terasology.engine.rendering.opengl.ScalingFactors.FULL_SCALE;
import static org.terasology.engine.rendering.opengl.fbms.DisplayResolutionDependentFbo.POST_FBO_REGENERATION;

/**
 * Instances of this node work in tandem with instances of the BlurredAmbientOcclusionNode class.
 * Together they constitute an ambient occlusion pass.
 *
 * This particular node generates a first, sharper ambient occlusion output. Subsequently that's
 * used by the BlurredAmbientOcclusionNode to make it softer.
 *
 * At this stage only the output of BlurredAmbientOcclusionNode is used to enhance the image eventually
 * shown on screen to the user. It is currently not possible to use the sharper output produced by
 * this node alone, i.e. to have lower quality but faster ambient occlusions.
 *
 * Ambient occlusion is a subtle visual effect that makes the rendering of the world more pleasing
 * at the cost of some additional milliseconds per frame. Disabling it may lead to increased frame
 * rate while the gameplay remains unaffected.
 *
 * See http://en.wikipedia.org/wiki/Ambient_occlusion for more information on this technique.
 */
public class AmbientOcclusionNode extends ConditionDependentNode {
    public static final SimpleUri SSAO_FBO_URI = new SimpleUri("engine:fbo.ssao");
    public static final int SSAO_KERNEL_ELEMENTS = 32;
    public static final int SSAO_NOISE_SIZE = 4;
    private static final ResourceUrn SSAO_MATERIAL_URN = new ResourceUrn("CoreRendering:ssao");
    private static final float NOISE_TEXEL_SIZE = 0.25f;

    private Material ssaoMaterial;
    private float outputFboWidth;
    private float outputFboHeight;

    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.01f, max = 12.0f)
    private float ssaoStrength = 1.75f;
    @SuppressWarnings("FieldCanBeLocal")
    @Range(min = 0.1f, max = 25.0f)
    private float ssaoRad = 1.5f;

    private FBO ssaoFbo;

    private Camera activeCamera;

    private final Random randomGenerator = new FastRandom();

    private FloatBuffer ssaoSamples;
    private Mesh renderQuad;

    public AmbientOcclusionNode(String nodeUri, Name providingModule, Context context) {
        super(nodeUri, providingModule, context);

        activeCamera = worldRenderer.getActiveCamera();

        RenderingConfig renderingConfig = context.get(Config.class).getRendering();
        renderingConfig.subscribe(RenderingConfig.SSAO, this);
        requiresCondition(renderingConfig::isSsao);
        addOutputFboConnection(1);
        addOutputBufferPairConnection(1);


        this.renderQuad = Assets.get(new ResourceUrn("engine:ScreenQuad"), Mesh.class)
                .orElseThrow(() -> new RuntimeException("Failed to resolve render Quad"));
    }

    @Override
    public void setDependencies(Context context) {
        addDesiredStateChange(new EnableMaterial(SSAO_MATERIAL_URN));
        ssaoMaterial = getMaterial(SSAO_MATERIAL_URN);

        DisplayResolutionDependentFbo displayResolutionDependentFBOs = context.get(DisplayResolutionDependentFbo.class);
        ssaoFbo = requiresFbo(new FboConfig(SSAO_FBO_URI, FULL_SCALE, FBO.Type.DEFAULT), displayResolutionDependentFBOs);

        addOutputFboConnection(1, ssaoFbo);

        addDesiredStateChange(new BindFbo(ssaoFbo));
        addDesiredStateChange(new SetViewportToSizeOf(ssaoFbo));
        displayResolutionDependentFBOs.subscribe(POST_FBO_REGENERATION, this);

        retrieveFboDimensions();
        // TODO: check for input textures brought in by the material

        BufferPairConnection bufferPairConnection = getInputBufferPairConnection(1);
        FBO lastUpdatedGBuffer = bufferPairConnection.getBufferPair().getPrimaryFbo();
        addOutputBufferPairConnection(1, bufferPairConnection);

        int texId = 0;
        addDesiredStateChange(new SetInputTextureFromFbo(texId++, lastUpdatedGBuffer, DepthStencilTexture, displayResolutionDependentFBOs,
                SSAO_MATERIAL_URN, "texDepth"));
        addDesiredStateChange(new SetInputTextureFromFbo(texId++, lastUpdatedGBuffer, NormalsTexture, displayResolutionDependentFBOs,
                SSAO_MATERIAL_URN, "texNormals"));
        addDesiredStateChange(new SetInputTexture2D(texId, generateNoiseTexture().getId(), SSAO_MATERIAL_URN, "texNoise"));

        if (ssaoSamples == null) {
            createSamplesBuffer();
        }
    }

    /**
     * If Ambient Occlusion is enabled in the render settings, this method generates and
     * stores the necessary images into their own FBOs. The stored images are eventually
     * combined with others.
     * <p>
     * For further information on Ambient Occlusion see: http://en.wikipedia.org/wiki/Ambient_occlusion
     */
    @Override
    public void process() {
        PerformanceMonitor.startActivity("rendering/" + getUri());

        ssaoMaterial.setFloat4("ssaoSettings", ssaoStrength, ssaoRad, 0.0f, 0.0f, true);

        ssaoMaterial.setMatrix4("invProjMatrix", activeCamera.getInverseProjectionMatrix(), true);
        ssaoMaterial.setMatrix4("projMatrix", activeCamera.getProjectionMatrix(), true);

        ssaoMaterial.setFloat2("texelSize", 1.0f / outputFboWidth, 1.0f / outputFboHeight, true);
        ssaoMaterial.setFloat2("noiseTexelSize", NOISE_TEXEL_SIZE, NOISE_TEXEL_SIZE, true);

        ssaoMaterial.setFloat3("ssaoSamples", ssaoSamples);

        this.renderQuad.render();

        PerformanceMonitor.endActivity();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        String propertyName = event.getPropertyName();

        switch (propertyName) {
            case RenderingConfig.SSAO:
                super.propertyChange(event);
                break;

            case POST_FBO_REGENERATION:
                retrieveFboDimensions();
                break;

            // default: no other cases are possible - see subscribe operations in initialize().
        }
    }

    private void retrieveFboDimensions() {
        outputFboWidth = ssaoFbo.width();
        outputFboHeight = ssaoFbo.height();
    }

    private void createSamplesBuffer() {
        ssaoSamples = BufferUtils.createFloatBuffer(SSAO_KERNEL_ELEMENTS * 3);

        for (int i = 0; i < SSAO_KERNEL_ELEMENTS; ++i) {
            Vector3f vec = new Vector3f(
                    randomGenerator.nextFloat(-1.0f, 1.0f),
                    randomGenerator.nextFloat(-1.0f, 1.0f),
                    randomGenerator.nextFloat()
            );

            vec.normalize();
            vec.mul(randomGenerator.nextFloat(0.0f, 1.0f));
            float scale = i / (float) SSAO_KERNEL_ELEMENTS;
            scale = TeraMath.lerp(0.25f, 1.0f, scale * scale);

            vec.mul(scale);

            ssaoSamples.put(vec.x);
            ssaoSamples.put(vec.y);
            ssaoSamples.put(vec.z);
        }

        ssaoSamples.flip();
    }

    private Texture generateNoiseTexture() {
        Optional<Texture> texture = Assets.getTexture("engine:ssaoNoise");
        if (!texture.isPresent()) {
            ByteBuffer noiseValues = BufferUtils.createByteBuffer(SSAO_NOISE_SIZE * SSAO_NOISE_SIZE * 4);

            for (int i = 0; i < SSAO_NOISE_SIZE * SSAO_NOISE_SIZE; ++i) {
                Vector3f noiseVector = new Vector3f(
                        randomGenerator.nextFloat(-1.0f, 1.0f),
                        randomGenerator.nextFloat(-1.0f, 1.0f),
                        0.0f);
                noiseVector.normalize();

                noiseValues.put((byte) ((noiseVector.x * 0.5 + 0.5) * 255.0f));
                noiseValues.put((byte) ((noiseVector.y * 0.5 + 0.5) * 255.0f));
                noiseValues.put((byte) ((noiseVector.z * 0.5 + 0.5) * 255.0f));
                noiseValues.put((byte) 0x0);
            }

            noiseValues.flip();

            return Assets.generateAsset(new ResourceUrn("engine:ssaoNoise"), new TextureData(SSAO_NOISE_SIZE, SSAO_NOISE_SIZE,
                    new ByteBuffer[]{noiseValues}, Texture.WrapMode.REPEAT, Texture.FilterMode.NEAREST), Texture.class);
        }
        return texture.get();
    }
}
