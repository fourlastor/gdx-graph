package com.gempukku.libgdx.graph.plugin.particles;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ObjectMap;
import com.gempukku.libgdx.graph.loader.GraphLoader;
import com.gempukku.libgdx.graph.pipeline.RenderPipeline;
import com.gempukku.libgdx.graph.pipeline.RenderPipelineBuffer;
import com.gempukku.libgdx.graph.pipeline.field.PipelineFieldType;
import com.gempukku.libgdx.graph.pipeline.producer.FullScreenRender;
import com.gempukku.libgdx.graph.pipeline.producer.PipelineRenderingContext;
import com.gempukku.libgdx.graph.pipeline.producer.node.*;
import com.gempukku.libgdx.graph.pipeline.producer.rendering.producer.ShaderContextImpl;
import com.gempukku.libgdx.graph.plugin.PluginPrivateDataSource;
import com.gempukku.libgdx.graph.plugin.particles.impl.GraphParticleEffectImpl;
import com.gempukku.libgdx.graph.plugin.particles.impl.GraphParticleEffectsImpl;
import com.gempukku.libgdx.graph.shader.common.CommonShaderConfiguration;
import com.gempukku.libgdx.graph.shader.common.PropertyShaderConfiguration;
import com.gempukku.libgdx.graph.shader.config.GraphConfiguration;
import com.gempukku.libgdx.graph.shader.property.PropertyLocation;
import com.gempukku.libgdx.graph.time.TimeProvider;

public class ParticlesShaderRendererPipelineNodeProducer extends SingleInputsPipelineNodeProducer {
    private static final GraphConfiguration[] configurations = new GraphConfiguration[]{
            new CommonShaderConfiguration(), new PropertyShaderConfiguration(),
            new ParticlesShaderConfiguration()};
    private final PluginPrivateDataSource pluginPrivateDataSource;

    public ParticlesShaderRendererPipelineNodeProducer(PluginPrivateDataSource pluginPrivateDataSource) {
        super(new ParticlesShaderRendererPipelineNodeConfiguration());
        this.pluginPrivateDataSource = pluginPrivateDataSource;
    }

    @Override
    public PipelineNode createNodeForSingleInputs(JsonValue data, ObjectMap<String, String> inputTypes, ObjectMap<String, String> outputTypes) {
        final ShaderContextImpl shaderContext = new ShaderContextImpl(pluginPrivateDataSource);

        final Array<ParticlesGraphShader> particleShaders = new Array<>();
        final JsonValue shaderDefinitions = data.get("shaders");

        final ObjectMap<String, PipelineNode.FieldOutput<?>> result = new ObjectMap<>();
        final DefaultFieldOutput<RenderPipeline> output = new DefaultFieldOutput<>(PipelineFieldType.RenderPipeline);
        result.put("output", output);

        return new SingleInputsPipelineNode(result) {
            private FullScreenRender fullScreenRender;
            private TimeProvider timeProvider;
            private GraphParticleEffectsImpl particleEffects;

            @Override
            public void initializePipeline(PipelineDataProvider pipelineDataProvider) {
                fullScreenRender = pipelineDataProvider.getFullScreenRender();
                timeProvider = pipelineDataProvider.getTimeProvider();
                particleEffects = pipelineDataProvider.getPrivatePluginData(GraphParticleEffectsImpl.class);

                for (JsonValue shaderDefinition : shaderDefinitions) {
                    String tag = shaderDefinition.getString("tag");
                    JsonValue shaderGraph = shaderDefinition.get("shader");
                    Gdx.app.debug("Shader", "Building shader with tag: " + tag);
                    final ParticlesGraphShader graphShader = GraphLoader.loadGraph(shaderGraph, new ParticlesShaderLoaderCallback(tag, pipelineDataProvider.getWhitePixel().texture, configurations), PropertyLocation.Uniform);
                    particleShaders.add(graphShader);
                }

                for (ParticlesGraphShader particleShader : particleShaders) {
                    particleEffects.registerEffect(particleShader.getTag(), particleShader);
                }
            }

            private boolean usesDepth() {
                for (ParticlesGraphShader particleShader : particleShaders) {
                    if (particleShader.isUsingDepthTexture()) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void executeNode(PipelineRenderingContext pipelineRenderingContext, PipelineRequirementsCallback pipelineRequirementsCallback) {
                final PipelineNode.FieldOutput<Boolean> processorEnabled = (PipelineNode.FieldOutput<Boolean>) inputs.get("enabled");
                final PipelineNode.FieldOutput<Camera> cameraInput = (PipelineNode.FieldOutput<Camera>) inputs.get("camera");
                final PipelineNode.FieldOutput<RenderPipeline> renderPipelineInput = (PipelineNode.FieldOutput<RenderPipeline>) inputs.get("input");

                boolean enabled = processorEnabled == null || processorEnabled.getValue();

                RenderPipeline renderPipeline = renderPipelineInput.getValue();

                if (enabled) {
                    boolean usesDepth = usesDepth();

                    boolean needsSceneColor = false;
                    for (ParticlesGraphShader particleShader : particleShaders) {
                        if (particleShader.isUsingColorTexture()) {
                            needsSceneColor = true;
                            break;
                        }
                    }

                    RenderPipelineBuffer currentBuffer = renderPipeline.getDefaultBuffer();

                    if (usesDepth) {
                        renderPipeline.enrichWithDepthBuffer(currentBuffer);
                    }

                    if (cameraInput != null) {
                        Camera camera = cameraInput.getValue();
                        shaderContext.setCamera(camera);
                    }

                    shaderContext.setTimeProvider(timeProvider);
                    shaderContext.setRenderWidth(currentBuffer.getWidth());
                    shaderContext.setRenderHeight(currentBuffer.getHeight());

                    RenderPipelineBuffer sceneColorBuffer = null;
                    if (needsSceneColor) {
                        sceneColorBuffer = setupColorTexture(renderPipeline, currentBuffer, pipelineRenderingContext);
                    }

                    currentBuffer.beginColor();

                    for (ParticlesGraphShader particleShader : particleShaders) {
                        String tag = particleShader.getTag();
                        if (particleEffects.hasEffects(tag)) {
                            shaderContext.setGlobalPropertyContainer(particleEffects.getGlobalPropertyContainer(tag));
                            particleShader.begin(shaderContext, pipelineRenderingContext.getRenderContext());
                            for (GraphParticleEffectImpl particleEffect : particleEffects.getParticleEffects(tag)) {
                                if (particleEffect.getRenderableParticleEffect().isRendered(shaderContext.getCamera(), tag)) {
                                    shaderContext.setLocalPropertyContainer(particleEffect.getPropertyContainer());
                                    particleEffect.render(particleShader, shaderContext);
                                }
                            }
                            particleShader.end();
                        }
                    }

                    currentBuffer.endColor();

                    if (sceneColorBuffer != null)
                        renderPipeline.returnFrameBuffer(sceneColorBuffer);
                }

                output.setValue(renderPipeline);
            }

            private RenderPipelineBuffer setupColorTexture(final RenderPipeline renderPipeline, final RenderPipelineBuffer currentBuffer,
                                                           PipelineRenderingContext pipelineRenderingContext) {
                RenderPipelineBuffer sceneColorBuffer = renderPipeline.getNewFrameBuffer(currentBuffer, Color.BLACK);
                shaderContext.setColorTexture(sceneColorBuffer.getColorBufferTexture());
                renderPipeline.drawTexture(currentBuffer, sceneColorBuffer, pipelineRenderingContext, fullScreenRender);
                return sceneColorBuffer;
            }

            @Override
            public void dispose() {
                for (ParticlesGraphShader particleShader : particleShaders) {
                    particleShader.dispose();
                }
            }
        };
    }
}
