/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.trustyai.explainability.integrationtests.pmml;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.kie.api.pmml.PMML4Result;
import org.kie.pmml.api.runtime.PMMLRuntime;
import org.kie.trustyai.explainability.Config;
import org.kie.trustyai.explainability.local.lime.LimeConfig;
import org.kie.trustyai.explainability.local.lime.LimeExplainer;
import org.kie.trustyai.explainability.local.lime.optim.LimeConfigOptimizer;
import org.kie.trustyai.explainability.metrics.ExplainabilityMetrics;
import org.kie.trustyai.explainability.model.DataDistribution;
import org.kie.trustyai.explainability.model.Feature;
import org.kie.trustyai.explainability.model.FeatureFactory;
import org.kie.trustyai.explainability.model.Output;
import org.kie.trustyai.explainability.model.PerturbationContext;
import org.kie.trustyai.explainability.model.Prediction;
import org.kie.trustyai.explainability.model.PredictionInput;
import org.kie.trustyai.explainability.model.PredictionInputsDataDistribution;
import org.kie.trustyai.explainability.model.PredictionOutput;
import org.kie.trustyai.explainability.model.PredictionProvider;
import org.kie.trustyai.explainability.model.Saliency;
import org.kie.trustyai.explainability.model.SimplePrediction;
import org.kie.trustyai.explainability.model.Type;
import org.kie.trustyai.explainability.model.Value;
import org.kie.trustyai.explainability.utils.DataUtils;
import org.kie.trustyai.explainability.utils.ValidationUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.kie.pmml.evaluator.assembler.factories.PMMLRuntimeFactoryInternal.getPMMLRuntime;

class PmmlRegressionCategoricalLimeExplainerTest {

    private static final String[] CATEGORY_ONE = new String[] { "red", "blue", "green", "yellow", "orange" };
    private static final String[] CATEGORY_TWO = new String[] { "classA", "classB", "classC" };
    private static PMMLRuntime categoricalVariableRegressionRuntime;

    @BeforeAll
    static void setUpBefore() throws URISyntaxException {
        categoricalVariableRegressionRuntime = getPMMLRuntime(ResourceReaderUtils.getResourceAsFile("categoricalvariablesregression/categoricalVariablesRegression.pmml"));
    }

    @Disabled("See KOGITO-6154")
    @Test
    void testPMMLRegressionCategorical() throws Exception {

        PredictionInput input = getTestInput();

        Random random = new Random();
        LimeConfig limeConfig = new LimeConfig()
                .withSamples(10)
                .withAdaptiveVariance(true)
                .withPerturbationContext(new PerturbationContext(0L, random, 1));
        LimeExplainer limeExplainer = new LimeExplainer(limeConfig);
        PredictionProvider model = getModel();
        List<PredictionOutput> predictionOutputs = model.predictAsync(List.of(input))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        assertThat(predictionOutputs)
                .isNotNull()
                .isNotEmpty();
        PredictionOutput output = predictionOutputs.get(0);
        assertThat(output).isNotNull();
        Prediction prediction = new SimplePrediction(input, output);
        Map<String, Saliency> saliencyMap = limeExplainer.explainAsync(prediction, model)
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit()).getSaliencies();
        for (Saliency saliency : saliencyMap.values()) {
            assertThat(saliency).isNotNull();
            double v = ExplainabilityMetrics.impactScore(model, prediction, saliency.getTopFeatures(2));
            assertThat(v).isEqualTo(1d);
        }
        assertDoesNotThrow(() -> ValidationUtils.validateLocalSaliencyStability(model, prediction, limeExplainer, 1,
                0.5, 0.5));

        List<PredictionInput> inputs = getSamples();
        DataDistribution distribution = new PredictionInputsDataDistribution(inputs);
        String decision = "result";
        int k = 1;
        int chunkSize = 2;
        double f1 = ExplainabilityMetrics.getLocalSaliencyF1(decision, model, limeExplainer, distribution, k, chunkSize);
        AssertionsForClassTypes.assertThat(f1).isBetween(0d, 1d);
    }

    @Disabled("See KOGITO-6154")
    @Test
    void testExplanationStabilityWithOptimization() throws ExecutionException, InterruptedException, TimeoutException {
        PredictionProvider model = getModel();

        List<PredictionInput> samples = getSamples();
        List<PredictionOutput> predictionOutputs = model.predictAsync(samples.subList(0, 5)).get();
        List<Prediction> predictions = DataUtils.getPredictions(samples, predictionOutputs);
        long seed = 0;
        LimeConfigOptimizer limeConfigOptimizer = new LimeConfigOptimizer().withDeterministicExecution(true);
        Random random = new Random();
        PerturbationContext perturbationContext = new PerturbationContext(seed, random, 1);
        LimeConfig initialConfig = new LimeConfig()
                .withSamples(10)
                .withPerturbationContext(perturbationContext);
        LimeConfig optimizedConfig = limeConfigOptimizer.optimize(initialConfig, predictions, model);
        assertThat(optimizedConfig).isNotSameAs(initialConfig);

        LimeExplainer limeExplainer = new LimeExplainer(optimizedConfig);
        PredictionInput testPredictionInput = getTestInput();
        List<PredictionOutput> testPredictionOutputs = model.predictAsync(List.of(testPredictionInput))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        Prediction instance = new SimplePrediction(testPredictionInput, testPredictionOutputs.get(0));

        assertDoesNotThrow(() -> ValidationUtils.validateLocalSaliencyStability(model, instance, limeExplainer, 1,
                0.6, 0.6));
    }

    @Disabled("See KOGITO-6154")
    @Test
    void testExplanationImpactScoreWithOptimization() throws ExecutionException, InterruptedException, TimeoutException {
        PredictionProvider model = getModel();

        List<PredictionInput> samples = getSamples();
        List<PredictionOutput> predictionOutputs = model.predictAsync(samples.subList(0, 5)).get();
        List<Prediction> predictions = DataUtils.getPredictions(samples, predictionOutputs);
        long seed = 0;
        LimeConfigOptimizer limeConfigOptimizer = new LimeConfigOptimizer().withDeterministicExecution(true).forImpactScore();
        Random random = new Random();
        PerturbationContext perturbationContext = new PerturbationContext(seed, random, 1);
        LimeConfig initialConfig = new LimeConfig()
                .withSamples(10)
                .withPerturbationContext(perturbationContext);
        LimeConfig optimizedConfig = limeConfigOptimizer.optimize(initialConfig, predictions, model);
        assertThat(optimizedConfig).isNotSameAs(initialConfig);
    }

    @Disabled("See KOGITO-6154")
    @Test
    void testExplanationWeightedStabilityWithOptimization() throws ExecutionException, InterruptedException, TimeoutException {
        PredictionProvider model = getModel();

        List<PredictionInput> samples = getSamples();
        List<PredictionOutput> predictionOutputs = model.predictAsync(samples.subList(0, 5)).get();
        List<Prediction> predictions = DataUtils.getPredictions(samples, predictionOutputs);
        long seed = 0;
        LimeConfigOptimizer limeConfigOptimizer = new LimeConfigOptimizer().withDeterministicExecution(true).withWeightedStability(0.4, 0.6);
        Random random = new Random();
        random.setSeed(seed);
        PerturbationContext perturbationContext = new PerturbationContext(seed, random, 1);
        LimeConfig initialConfig = new LimeConfig()
                .withSamples(10)
                .withPerturbationContext(perturbationContext);
        LimeConfig optimizedConfig = limeConfigOptimizer.optimize(initialConfig, predictions, model);
        assertThat(optimizedConfig).isNotSameAs(initialConfig);

        LimeExplainer limeExplainer = new LimeExplainer(optimizedConfig);
        PredictionInput testPredictionInput = getTestInput();
        List<PredictionOutput> testPredictionOutputs = model.predictAsync(List.of(testPredictionInput))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        Prediction instance = new SimplePrediction(testPredictionInput, testPredictionOutputs.get(0));

        assertDoesNotThrow(() -> ValidationUtils.validateLocalSaliencyStability(model, instance, limeExplainer, 1,
                0.5, 0.7));
    }

    private PredictionProvider getModel() {
        return inputs -> CompletableFuture.supplyAsync(() -> {
            List<PredictionOutput> outputs = new ArrayList<>();
            for (PredictionInput input1 : inputs) {
                List<Feature> features1 = input1.getFeatures();
                CategoricalVariablesRegressionExecutor pmmlModel = new CategoricalVariablesRegressionExecutor(
                        features1.get(0).getValue().asString(), features1.get(1).getValue().asString());
                PMML4Result result = pmmlModel.execute(categoricalVariableRegressionRuntime);
                String score = result.getResultVariables().get("result").toString();
                PredictionOutput predictionOutput = new PredictionOutput(List.of(new Output("result", Type.NUMBER, new Value(score), 1d)));
                outputs.add(predictionOutput);
            }
            return outputs;
        });
    }

    private List<PredictionInput> getSamples() {
        List<PredictionInput> inputs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<Feature> fs = new ArrayList<>();
            fs.add(FeatureFactory.newCategoricalFeature("mapX", CATEGORY_ONE[i % CATEGORY_ONE.length]));
            fs.add(FeatureFactory.newCategoricalFeature("mapY", CATEGORY_TWO[i % CATEGORY_TWO.length]));
            inputs.add(new PredictionInput(fs));
        }
        return inputs;
    }

    private PredictionInput getTestInput() {
        List<Feature> features = new ArrayList<>();
        features.add(FeatureFactory.newCategoricalFeature("mapX", CATEGORY_ONE[0]));
        features.add(FeatureFactory.newCategoricalFeature("mapY", CATEGORY_TWO[1]));
        return new PredictionInput(features);
    }
}
