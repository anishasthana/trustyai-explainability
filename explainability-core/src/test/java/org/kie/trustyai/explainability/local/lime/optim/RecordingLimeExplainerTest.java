/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
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
package org.kie.trustyai.explainability.local.lime.optim;

import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kie.trustyai.explainability.Config;
import org.kie.trustyai.explainability.TestUtils;
import org.kie.trustyai.explainability.local.lime.LimeConfig;
import org.kie.trustyai.explainability.model.*;
import org.kie.trustyai.explainability.utils.models.TestModels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class RecordingLimeExplainerTest {

    @Test
    void testRecordedPredictions() {
        RecordingLimeExplainer recordingLimeExplainer = new RecordingLimeExplainer(10);
        List<Prediction> allPredictions = new ArrayList<>();
        PredictionProvider model = mock(PredictionProvider.class);
        for (int i = 0; i < 15; i++) {
            Prediction prediction = mock(Prediction.class);
            allPredictions.add(prediction);
            try {
                recordingLimeExplainer.explainAsync(prediction, model).get(Config.DEFAULT_ASYNC_TIMEOUT,
                        Config.DEFAULT_ASYNC_TIMEUNIT);
            } catch (Exception e) {
                // ignored for the sake of the test
            }
        }
        assertThat(allPredictions).hasSize(15);
        List<Prediction> recordedPredictions = recordingLimeExplainer.getRecordedPredictions();
        assertThat(recordedPredictions).hasSize(10);
        // only the last 10 predictions are kept
        assertThat(allPredictions.subList(5, 15)).isEqualTo(recordedPredictions);
    }

    @Test
    void testParallel() throws InterruptedException, ExecutionException, TimeoutException {
        int capacity = 10;
        RecordingLimeExplainer recordingLimeExplainer = new RecordingLimeExplainer(capacity);
        PredictionProvider model = mock(PredictionProvider.class);

        Callable<?> callable = () -> {
            for (int i = 0; i < 10000; i++) {
                Prediction prediction = mock(Prediction.class);
                try {
                    recordingLimeExplainer.explainAsync(prediction, model).get(Config.DEFAULT_ASYNC_TIMEOUT,
                            Config.DEFAULT_ASYNC_TIMEUNIT);
                } catch (Exception e) {
                    // ignored for the sake of the test
                }
            }
            return null;
        };
        Collection<Future<?>> futures = new ArrayList<>();
        ExecutorService executorService = Executors.newCachedThreadPool();
        for (int i = 0; i < 4; i++) {
            futures.add(executorService.submit(callable));
        }
        for (Future<?> f : futures) {
            f.get(1, TimeUnit.MINUTES);
        }
        assertThat(recordingLimeExplainer.getRecordedPredictions().size()).isEqualTo(capacity);
    }

    @Test
    void testQueue() {
        RecordingLimeExplainer.FixedSizeConcurrentLinkedDeque<String> queue = new RecordingLimeExplainer.FixedSizeConcurrentLinkedDeque<>(5);
        String[] strings = "a b c d e f g f".split(" ");
        for (String s : strings) {
            queue.offer(s);
        }
        assertThat(queue).containsExactly("c d e f g".split(" "));
    }

    @ParameterizedTest
    @ValueSource(longs = { 0 })
    void testAutomaticConfigOptimization(long seed) throws Exception {
        PredictionProvider model = TestModels.getSumThresholdModel(10, 10);
        PerturbationContext pc = new PerturbationContext(seed, new Random(), 1);
        LimeConfig config = new LimeConfig().withPerturbationContext(pc);
        RecordingLimeExplainer limeExplainer = new RecordingLimeExplainer(2);
        for (int i = 0; i < 50; i++) {
            List<Feature> features = new LinkedList<>();
            features.add(TestUtils.getMockedNumericFeature(Type.NUMBER.randomValue(pc).asNumber()));
            features.add(TestUtils.getMockedNumericFeature(Type.NUMBER.randomValue(pc).asNumber()));
            features.add(TestUtils.getMockedNumericFeature(Type.NUMBER.randomValue(pc).asNumber()));
            PredictionInput input = new PredictionInput(features);
            List<PredictionOutput> outputs = model.predictAsync(List.of(input))
                    .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
            Prediction prediction = new SimplePrediction(input, outputs.get(0));

            SaliencyResults saliencyMap = limeExplainer.explainAsync(prediction, model).toCompletableFuture()
                    .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
            for (Saliency saliency : saliencyMap.getSaliencies().values()) {
                assertNotNull(saliency);
            }
        }
        LimeConfig optimizedConfig = limeExplainer.getExecutionConfig();
        assertThat(optimizedConfig).isNotEqualTo(config);
    }

    @Test
    void testEmptyInput() {
        RecordingLimeExplainer recordingLimeExplainer = new RecordingLimeExplainer(10);
        PredictionProvider model = mock(PredictionProvider.class);
        Prediction prediction = mock(Prediction.class);
        assertThatCode(() -> recordingLimeExplainer.explainAsync(prediction, model)).hasMessage("cannot explain a prediction whose input is empty");
    }

    @Test
    void testExplainNonOptimized() throws ExecutionException, InterruptedException, TimeoutException {
        RecordingLimeExplainer limeExplainer = new RecordingLimeExplainer(10);
        List<Feature> features = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            features.add(TestUtils.getMockedNumericFeature(i));
        }
        PredictionInput input = new PredictionInput(features);
        PredictionProvider model = TestModels.getSumSkipModel(0);
        PredictionOutput output = model.predictAsync(List.of(input))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit())
                .get(0);
        Prediction prediction = new SimplePrediction(input, output);
        SaliencyResults saliencyMap = limeExplainer.explainAsync(prediction, model)
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        assertNotNull(saliencyMap);
    }

    @Test
    void testEquals() {
        RecordingLimeExplainer o1 = new RecordingLimeExplainer(10);
        RecordingLimeExplainer o2 = new RecordingLimeExplainer(10);
        assertThat(o1).isNotEqualTo(o2);
        LimeConfig config = new LimeConfig();
        RecordingLimeExplainer o3 = new RecordingLimeExplainer(config, 10);
        RecordingLimeExplainer o4 = new RecordingLimeExplainer(config, 10);
        assertThat(o3).isEqualTo(o4);
    }

}
