/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
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

package org.kie.trustyai.explainability.local.shap;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kie.trustyai.explainability.Config;
import org.kie.trustyai.explainability.model.*;
import org.kie.trustyai.explainability.model.domain.CategoricalFeatureDomain;
import org.kie.trustyai.explainability.utils.MatrixUtilsExtensions;
import org.kie.trustyai.explainability.utils.models.TestModels;

import static org.junit.jupiter.api.Assertions.*;

class ShapKernelExplainerTest {
    double[][] backgroundRaw = {
            { 1., 2., 3., -4., 5. },
            { 10., 11., 12., -4., 13. },
            { 2., 3, 4., -4., 6. },
    };
    double[][] toExplainRaw = {
            { 5., 6., 7., -4., 8. },
            { 11., 12., 13., -5., 14. },
            { 0., 0, 1., 4., 2. },
    };

    // no variance test case matrices  ===============
    double[][] backgroundNoVariance = {
            { 1., 2., 3. },
            { 1., 2., 3. }
    };

    double[][] toExplainZeroVariance = {
            { 1., 2., 3. },
            { 1., 2., 3. },
    };

    double[][][] zeroVarianceOneOutputSHAP = {
            { { 0., 0., 0. } },
            { { 0., 0., 0. } },
    };

    double[][][] zeroVarianceMultiOutputSHAP = {
            { { 0., 0., 0. }, { 0., 0., 0 } },
            { { 0., 0., 0. }, { 0., 0., 0 } },
    };

    // single variance test case matrices ===============
    double[][] toExplainOneVariance = {
            { 3., 2., 3. },
            { 1., 2., 2. },
    };

    double[][][] oneVarianceOneOutputSHAP = {
            { { 2., 0., 0. } },
            { { 0., 0., -1. } },
    };

    double[][][] oneVarianceMultiOutputSHAP = {
            { { 4., 0., 0. }, { 2., 0., 0. } },
            { { 0., 0., -2 }, { 0., 0., -1. } },
    };

    // multi variance, one output logit test case matrices ===============
    double[][] toExplainLogit = {
            { 0.1, 0.12, 0.14, -0.08, 0.16 },
            { 0.22, 0.24, 0.26, -0.1, 0.38 },
            { -0.1, 0., 0.02, 0.1, 0.04 }
    };

    double[][] backgroundLogit = {
            { 0.02380952, 0.04761905, 0.07142857, -0.0952381, 0.11904762 },
            { 0.23809524, 0.26190476, 0.28571429, -0.0952381, 0.30952381 },
            { 0.04761905, 0.07142857, 0.11904762, -0.0952381, 0.14285714 }
    };

    double[][][] logitSHAP = {
            { { -0.01420862, 0., -0.08377778, 0.06825253, -0.13625127 } },
            { { 0.50970797, 0., 0.44412765, -0.02169177, 0.80832232 } },
            { { Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN } }
    };

    // multiple variance test case matrices ===============
    double[][][] multiVarianceOneOutputSHAP = {
            { { 0.66666667, 0., 0.66666667, 0., 0. } },
            { { 6.66666667, 0., 6.66666667, -1., 6. } },
            { { -4.33333333, 0., -5.33333333, 8., -6. } },
    };

    double[][][] multiVarianceMultiOutputSHAP = {
            { { 1.333333333, 0., 1.33333333, 0., 0. }, { 0.66666667, 0., 0.66666667, 0., 0. }, },
            { { 13.333333333, 0., 13.333333333, -2., 12. }, { 6.66666667, 0., 6.66666667, -1., 6. }, },
            { { -8.6666666667, 0., -10.666666666, 16., -12. }, { -4.33333333, 0., -5.33333333, 8., -6. }, },
    };

    PerturbationContext pc = new PerturbationContext(new Random(0), 0);
    ShapConfig.Builder testConfig = ShapConfig.builder().withLink(ShapConfig.LinkType.IDENTITY).withPC(pc);

    // test helper functions ===========================================================================================
    // create a list of prediction inputs from double matrix
    private List<PredictionInput> createPIFromMatrix(double[][] m) {
        List<PredictionInput> pis = new ArrayList<>();
        int[] shape = new int[] { m.length, m[0].length };
        for (int i = 0; i < shape[0]; i++) {
            List<Feature> fs = new ArrayList<>();
            for (int j = 0; j < shape[1]; j++) {
                fs.add(FeatureFactory.newNumericalFeature("f", m[i][j]));
            }
            pis.add(new PredictionInput(fs));
        }
        return pis;
    }

    private RealMatrix[] saliencyToMatrix(Map<String, Saliency> saliencies) {
        return saliencyToMatrix(saliencies, false);
    }

    private RealMatrix[] saliencyToMatrix(Map<String, Saliency> saliencies, boolean withBackground) {
        String[] keySet = saliencies.keySet().toArray(String[]::new);
        RealMatrix emptyMatrix = MatrixUtils.createRealMatrix(
                new double[saliencies.size()][saliencies
                        .get(keySet[0])
                        .getPerFeatureImportance().size() - (withBackground ? 0 : 1)]);
        RealMatrix[] out = new RealMatrix[] { emptyMatrix.copy(), emptyMatrix.copy() };
        for (int i = 0; i < keySet.length; i++) {
            List<FeatureImportance> fis = saliencies.get(keySet[i]).getPerFeatureImportance();
            for (int j = 0; j < fis.size() - (withBackground ? 0 : 1); j++) {
                out[0].setEntry(i, j, fis.get(j).getScore());
                out[1].setEntry(i, j, fis.get(j).getConfidence());
            }
        }
        return out;
    }

    /*
     * given a specific model, config, background, explanations, and expected shap values,
     * test that the computed shape values match expected shap values
     */
    private void shapTestCase(PredictionProvider model, ShapConfig skConfig,
            double[][] toExplainRaw, double[][][] expected)
            throws InterruptedException, TimeoutException, ExecutionException {

        // establish background data and desired data to explain

        List<PredictionInput> toExplain = createPIFromMatrix(toExplainRaw);

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get(5, TimeUnit.SECONDS);
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // evaluate if the explanations match the expected value
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        for (int i = 0; i < toExplain.size(); i++) {
            //explanations shape: outputSize x nfeatures
            Map<String, Saliency> explanationSaliencies = ske.explainAsync(predictions.get(i), model)
                    .get(5, TimeUnit.SECONDS).getSaliencies();
            RealMatrix explanations = saliencyToMatrix(explanationSaliencies)[0];
            for (int j = 0; j < explanations.getRowDimension(); j++) {
                assertArrayEquals(expected[i][j], explanations.getRow(j), 1e-6);
            }
        }
    }

    /*
     * given a specific model, config, background, explanations, ske, and expected shap values,
     * test that the computed shape values match expected shap values
     */
    private void shapTestCase(PredictionProvider model, ShapKernelExplainer ske,
            double[][] toExplainRaw, double[][][] expected)
            throws InterruptedException, TimeoutException, ExecutionException {

        // establish background data and desired data to explain
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainRaw);

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get(5, TimeUnit.SECONDS);
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // evaluate if the explanations match the expected value
        for (int i = 0; i < toExplain.size(); i++) {
            //explanations shape: outputSize x nfeatures
            Map<String, Saliency> explanationSaliencies = ske.explainAsync(predictions.get(i), model)
                    .get(5, TimeUnit.SECONDS).getSaliencies();
            RealMatrix explanations = saliencyToMatrix(explanationSaliencies)[0];
            for (int j = 0; j < explanations.getRowDimension(); j++) {
                assertArrayEquals(expected[i][j], explanations.getRow(j), 1e-6);
            }
        }
    }

    // Single output models ============================================================================================
    // test a single output model with no varying features
    @Test
    void testNoVarianceOneOutput() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundNoVariance);
        ShapConfig skConfig = testConfig.withBackground(background).withNSamples(100).build();
        shapTestCase(model, skConfig, toExplainZeroVariance, zeroVarianceOneOutputSHAP);
    }

    // test a single output model with one varying feature
    @Test
    void testOneVarianceOneOutput() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundNoVariance);
        ShapConfig skConfig = testConfig.withBackground(background).withNSamples(100).build();
        shapTestCase(model, skConfig, toExplainOneVariance, oneVarianceOneOutputSHAP);
    }

    // test a single output model with many varying features
    @Test
    void testMultiVarianceOneOutput() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundRaw);
        ShapConfig skConfig = testConfig.withBackground(background).withNSamples(35).build();
        shapTestCase(model, skConfig, toExplainRaw, multiVarianceOneOutputSHAP);
    }

    // test a single output model with many varying features and logit link
    @Test
    void testMultiVarianceOneOutputLogit() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundLogit);
        ShapConfig skConfig = ShapConfig.builder()
                .withBackground(background)
                .withLink(ShapConfig.LinkType.LOGIT)
                .withNSamples(100)
                .withPC(pc)
                .build();
        shapTestCase(model, skConfig, toExplainLogit, logitSHAP);
    }

    // Multi-output models =============================================================================================
    // test a multi-output model with no varying features
    @Test
    void testNoVarianceMultiOutput() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipTwoOutputModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundNoVariance);
        ShapConfig skConfig = testConfig.withBackground(background).build();
        shapTestCase(model, skConfig, toExplainZeroVariance, zeroVarianceMultiOutputSHAP);
    }

    // test a multi-output model with one varying feature
    @Test
    void testOneVarianceMultiOutput() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipTwoOutputModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundNoVariance);
        ShapConfig skConfig = testConfig.withBackground(background).build();
        shapTestCase(model, skConfig, toExplainOneVariance, oneVarianceMultiOutputSHAP);
    }

    // test a multi-output model with many varying features
    @Test
    void testMultiVarianceMultiOutput() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipTwoOutputModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundRaw);
        ShapConfig skConfig = testConfig.withBackground(background).build();
        shapTestCase(model, skConfig, toExplainRaw, multiVarianceMultiOutputSHAP);
    }

    // test that the last saliency is always the background
    @Test
    void testLastSaliencyIsBackground() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipTwoOutputModel(1);
        List<PredictionInput> background = createPIFromMatrix(backgroundRaw);
        ShapConfig skConfig = testConfig.withBackground(background).build();
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainRaw);

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get(5, TimeUnit.SECONDS);
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // evaluate if the explanations match the expected value
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        for (int i = 0; i < toExplain.size(); i++) {
            //explanations shape: outputSize x nfeatures
            Map<String, Saliency> explanationSaliencies = ske.explainAsync(predictions.get(i), model)
                    .get(5, TimeUnit.SECONDS).getSaliencies();
            for (Map.Entry<String, Saliency> entry : explanationSaliencies.entrySet()) {
                List<FeatureImportance> pfis = entry.getValue().getPerFeatureImportance();
                assertEquals("Background", pfis.get(pfis.size() - 1).getFeature().getName());
            }
        }
    }

    // Test cases where search space cannot be fully enumerated ========================================================
    @Test
    void testLargeBackground() throws InterruptedException, TimeoutException, ExecutionException {
        // establish background data and desired data to explain
        double[][] largeBackground = new double[100][10];
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 10; j++) {
                largeBackground[i][j] = i / 100. + j;
            }
        }
        double[][] toExplainLargeBackground = {
                { 0, 1., -2., 3.5, -4.1, 5.5, -12., .8, .11, 15. }
        };

        double[][][] expected = {
                { { -0.495, 0., -4.495, 0.005, -8.595, 0.005, -18.495,
                        -6.695, -8.385, 5.505 } }
        };

        List<PredictionInput> background = createPIFromMatrix(largeBackground);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainLargeBackground);

        PredictionProvider model = TestModels.getSumSkipModel(1);
        ShapConfig skConfig = testConfig.withBackground(background).build();

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // evaluate if the explanations match the expected value
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        for (int i = 0; i < toExplain.size(); i++) {
            Map<String, Saliency> explanationSaliencies = ske.explainAsync(predictions.get(i), model)
                    .get(5, TimeUnit.SECONDS).getSaliencies();
            RealMatrix[] explanationsAndConfs = saliencyToMatrix(explanationSaliencies);
            RealMatrix explanations = explanationsAndConfs[0];

            for (int j = 0; j < explanations.getRowDimension(); j++) {
                assertArrayEquals(expected[i][j], explanations.getRow(j), 1e-2);
            }
        }
    }

    @Test
    void testLargeBackground2() throws InterruptedException, TimeoutException, ExecutionException {
        // establish background data and desired data to explain
        double[][] largeBackground = new double[100][10];
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 10; j++) {
                largeBackground[i][j] = i / 100. + j;
            }
        }
        double[][] toExplainLargeBackground = {
                { 0, 1., -2., 3.5, -4.1, 5.5, -12., .8, .11, 15. }
        };

        double[][][] expected = {
                { { -0.495, 0., -4.495, 0.005, -8.595, 0.005, -18.495,
                        -6.695, -8.385, 5.505 } }
        };

        List<PredictionInput> background = createPIFromMatrix(largeBackground);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainLargeBackground);

        PredictionProvider model = TestModels.getSumSkipModel(1);
        ShapConfig skConfig = testConfig.withBackground(background).withNSamples(1000).build();

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // evaluate if the explanations match the expected value
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        for (int i = 0; i < toExplain.size(); i++) {
            Map<String, Saliency> explanationSaliencies = ske.explainAsync(predictions.get(i), model)
                    .get(5, TimeUnit.SECONDS).getSaliencies();
            RealMatrix[] explanationsAndConfs = saliencyToMatrix(explanationSaliencies);
            RealMatrix explanations = explanationsAndConfs[0];

            for (int j = 0; j < explanations.getRowDimension(); j++) {
                assertArrayEquals(expected[i][j], explanations.getRow(j), 1e-2);
            }
        }
    }

    @Test
    void testParallel() throws InterruptedException, ExecutionException {
        // establish background data and desired data to explain
        double[][] largeBackground = new double[100][10];
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 10; j++) {
                largeBackground[i][j] = i / 100. + j;
            }
        }
        double[][] toExplainLargeBackground = {
                { 0, 1., -2., 3.5, -4.1, 5.5, -12., .8, .11, 15. }
        };

        double[][][] expected = {
                { { -0.495, 0., -4.495, 0.005, -8.595, 0.005, -18.495,
                        -6.695, -8.385, 5.505 } }
        };

        List<PredictionInput> background = createPIFromMatrix(largeBackground);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainLargeBackground);

        PredictionProvider model = TestModels.getSumSkipModel(1);
        ShapConfig skConfig = testConfig.withBackground(background).build();

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // evaluate if the explanations match the expected value
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        CompletableFuture<SaliencyResults> explanationsCF = ske.explainAsync(predictions.get(0), model);

        ExecutorService executor = ForkJoinPool.commonPool();
        executor.submit(() -> {
            Map<String, Saliency> explanationSaliencies = explanationsCF.join().getSaliencies();
            RealMatrix explanations = saliencyToMatrix(explanationSaliencies)[0];
            assertArrayEquals(expected[0][0], explanations.getRow(0), 1e-2);
        });
    }

    // Test cases with size errors ========================================================
    @Test
    void testTooLargeBackground() throws InterruptedException, TimeoutException, ExecutionException {
        // establish background data and desired data to explain
        double[][] tooLargeBackground = new double[10][10];
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                tooLargeBackground[i][j] = i / 10. + j;
            }
        }
        double[][] toExplainTooSmall = {
                { 0, 1., 2., 3., 4. }
        };

        List<PredictionInput> background = createPIFromMatrix(tooLargeBackground);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainTooSmall);

        PredictionProvider model = TestModels.getSumSkipModel(1);
        ShapConfig skConfig = testConfig.withBackground(background).build();

        //initialize explainer
        List<PredictionOutput> predictionOutputs = model
                .predictAsync(toExplain)
                .get(5, TimeUnit.SECONDS);
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // make sure we get an illegal argument exception because our background is bigger than the point to be explained
        Prediction p = predictions.get(0);
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        assertThrows(IllegalArgumentException.class, () -> ske.explainAsync(p, model));
    }

    // Test cases with prediction size mismatches ========================================================
    @Test
    void testPredictionWrongSize() throws InterruptedException, TimeoutException, ExecutionException {
        // establish background data and desired data to explain
        double[][] backgroundMat = new double[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                backgroundMat[i][j] = i / 5. + j;
            }
        }
        double[][] toExplainTooSmall = {
                { 0, 1., 2., 3., 4. }
        };

        List<PredictionInput> background = createPIFromMatrix(backgroundMat);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainTooSmall);

        PredictionProvider modelForPredictions = TestModels.getSumSkipTwoOutputModel(1);
        PredictionProvider modelForShap = TestModels.getSumSkipModel(1);
        ShapConfig skConfig = testConfig.withBackground(background).build();

        //initialize explainer
        List<PredictionOutput> predictionOutputs = modelForPredictions
                .predictAsync(toExplain)
                .get(5, TimeUnit.SECONDS);
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < predictionOutputs.size(); i++) {
            predictions.add(new SimplePrediction(toExplain.get(i), predictionOutputs.get(i)));
        }

        // make sure we get an illegal argument exception; our prediction to explain has a different shape t
        // than the background predictions will
        Prediction p = predictions.get(0);
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        assertThrows(ExecutionException.class, () -> ske.explainAsync(p, modelForShap).get());
    }

    // See if using the same explainer multiple times causes issues ====================================================
    @Test
    void testStateless() throws InterruptedException, TimeoutException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        ShapConfig skConfig1 = testConfig
                .withBackground(createPIFromMatrix(backgroundNoVariance))
                .withNSamples(100)
                .build();
        ShapConfig skConfig2 = testConfig.withBackground(createPIFromMatrix(backgroundRaw)).withNSamples(35).build();
        ShapConfig skConfig3 = ShapConfig.builder()
                .withBackground(createPIFromMatrix(backgroundLogit))
                .withLink(ShapConfig.LinkType.LOGIT)
                .withNSamples(100)
                .withPC(pc)
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig1);
        for (int i = 0; i < 10; i++) {
            shapTestCase(model, ske, toExplainOneVariance, oneVarianceOneOutputSHAP);
            ske.setConfig(skConfig2);
            shapTestCase(model, ske, toExplainRaw, multiVarianceOneOutputSHAP);
            ske.setConfig(skConfig3);
            shapTestCase(model, ske, toExplainLogit, logitSHAP);
            ske.setConfig(skConfig1);
        }
    }

    double[][] backgroundAllZeros = new double[100][6];

    double[][] toExplainAllOnes = {
            { 1., 1., 1., 1., 1, 1. }
    };

    //given a noisy model, expect the n% confidence window to include true value roughly n% of the time
    @ParameterizedTest
    @ValueSource(doubles = { .001, .1, .25, .5 })
    void testErrorBounds(double noise) throws InterruptedException, ExecutionException {
        for (double interval : new double[] { .95, .975, .99 }) {
            int[] testResults = new int[600];
            SplittableRandom rn = new SplittableRandom();
            PredictionProvider model = TestModels.getNoisySumModel(rn, noise, 64 * 100);
            for (int test = 0; test < 100; test++) {
                ShapConfig skConfig = testConfig
                        .withBackground(createPIFromMatrix(backgroundAllZeros))
                        .withConfidence(interval)
                        .build();
                List<PredictionInput> toExplain = createPIFromMatrix(toExplainAllOnes);
                ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
                List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
                Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
                Map<String, Saliency> saliencies = ske.explainAsync(p, model).get().getSaliencies();
                RealMatrix[] explanationsAndConfs = saliencyToMatrix(saliencies);
                RealMatrix explanations = explanationsAndConfs[0];
                RealMatrix confidence = explanationsAndConfs[1];

                for (int i = 0; i < explanations.getRowDimension(); i++) {
                    for (int j = 0; j < explanations.getColumnDimension(); j++) {
                        double conf = confidence.getEntry(i, j);
                        double exp = explanations.getEntry(i, j);

                        // see if true value falls into confidence interval
                        testResults[test * 6 + j] = (exp + conf) > 1.0 & 1.0 > (exp - conf) ? 1 : 0;
                    }
                }
            }

            // roughly interval% of the tests should be true
            double score = Arrays.stream(testResults).sum() / 600.;
            assertEquals(interval, score, .05);
        }
    }

    double[][] toExplainRegTests = { { 0.5488135, 0.71518937, 0.60276338, 0.54488318, 0.4236548, 0.64589411, 0.43758721, 0.891773, 0.96366276 },
    };
    double[][] backgroundRegTests = { { 0.38344152, 0.79172504, 0.52889492, 0.56804456, 0.92559664, 0.07103606, 0.0871293, 0.0202184, 0.83261985 },
            { 0.77815675, 0.87001215, 0.97861834, 0.79915856, 0.46147936, 0.78052918, 0.11827443, 0.63992102, 0.14335329 },
            { 0.94466892, 0.52184832, 0.41466194, 0.26455561, 0.77423369, 0.45615033, 0.56843395, 0.0187898, 0.6176355, },
            { 0.61209572, 0.616934, 0.94374808, 0.6818203, 0.3595079, 0.43703195, 0.6976312, 0.06022547, 0.66676672 },
            { 0.67063787, 0.21038256, 0.1289263, 0.31542835, 0.36371077, 0.57019677, 0.43860151, 0.98837384, 0.10204481 },
            { 0.20887676, 0.16130952, 0.65310833, 0.2532916, 0.46631077, 0.24442559, 0.15896958, 0.11037514, 0.65632959 },
            { 0.13818295, 0.19658236, 0.36872517, 0.82099323, 0.09710128, 0.83794491, 0.09609841, 0.97645947, 0.4686512, },
            { 0.97676109, 0.60484552, 0.73926358, 0.03918779, 0.28280696, 0.12019656, 0.2961402, 0.11872772, 0.31798318 },
            { 0.41426299, 0.0641475, 0.69247212, 0.56660145, 0.26538949, 0.52324805, 0.09394051, 0.5759465, 0.9292962, },
            { 0.31856895, 0.66741038, 0.13179786, 0.7163272, 0.28940609, 0.18319136, 0.58651293, 0.02010755, 0.82894003 },
    };

    private List<ShapConfig> getSks() {
        ShapConfig sk0 = testConfig
                .withBackground(createPIFromMatrix(backgroundRegTests))
                .withRegularizer(ShapConfig.RegularizerType.AIC)
                .build();
        ShapConfig sk1 = testConfig
                .withBackground(createPIFromMatrix(backgroundRegTests))
                .withRegularizer(ShapConfig.RegularizerType.BIC)
                .build();
        ShapConfig sk2 = testConfig
                .withBackground(createPIFromMatrix(backgroundRegTests))
                .withRegularizer(4)
                .build();
        ShapConfig sk3 = testConfig
                .withBackground(createPIFromMatrix(backgroundRegTests))
                .withRegularizer(7)
                .build();
        ShapConfig sk4 = testConfig
                .withBackground(createPIFromMatrix(backgroundRegTests))
                .withRegularizer(ShapConfig.RegularizerType.AUTO)
                .build();
        return List.of(sk0, sk1, sk2, sk3, sk4);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3 })
    void testRegularizations(int config) throws InterruptedException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainRegTests);
        RealMatrix toExplainMatrix = MatrixUtils.createRealMatrix(toExplainRegTests);
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        RealVector predictionOutputVector = MatrixUtilsExtensions.vectorFromPredictionOutput(predictionOutputs.get(0));
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        ShapConfig skConfig = getSks().get(config);
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        SaliencyResults shapResults = ske.explainAsync(p, model).get();
        Map<String, Saliency> saliencies = shapResults.getSaliencies();
        RealMatrix[] explanationsAndConfs = saliencyToMatrix(saliencies, true);
        RealMatrix explanations = explanationsAndConfs[0];

        double actualOut = predictionOutputVector.getEntry(0);
        double predOut = MatrixUtilsExtensions.sum(explanations.getRowVector(0));
        assertTrue(Math.abs(predOut - actualOut) < 1e-6);
    }

    // a deterministic, psuedorandom number generator based on Blum Blum Shub.
    // This allows for easy equivalence with Python tests
    public double[][] generateN(int i, int j, String seed) {
        BigInteger p = new BigInteger("26017");
        BigInteger q = new BigInteger("98893");

        BigInteger m = p.multiply(q);
        BigInteger curr = new BigInteger(seed);
        double[][] out = new double[i][j];
        for (int idx = 0; idx < i; idx++) {
            for (int jdx = 0; jdx < j; jdx++) {
                curr = curr.pow(2).mod(m);
                out[idx][jdx] = curr.longValue() / 1e9;
            }
        }
        return out;
    }

    @Test
    void testManyFeatureRegularization() throws ExecutionException, InterruptedException {
        RealVector modelWeights = MatrixUtils.createRealMatrix(generateN(1, 25, "5021")).getRowVector(0);
        PredictionProvider model = TestModels.getLinearModel(modelWeights.toArray());
        RealMatrix data = MatrixUtils.createRealMatrix(generateN(101, 25, "8629"));
        List<PredictionInput> toExplain = createPIFromMatrix(data.getRowMatrix(100).getData());
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        RealVector predictionOutputVector = MatrixUtilsExtensions.vectorFromPredictionOutput(predictionOutputs.get(0));
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        List<PredictionInput> bg = createPIFromMatrix(data.getSubMatrix(0, 99, 0, 24).getData());

        List<ShapConfig.Builder> testConfigs = List.of(
                testConfig.copy().withBackground(bg).withRegularizer(ShapConfig.RegularizerType.AIC),
                testConfig.copy().withBackground(bg).withRegularizer(ShapConfig.RegularizerType.BIC),
                testConfig.copy().withBackground(bg).withRegularizer(10),
                testConfig.copy().withBackground(bg).withRegularizer(ShapConfig.RegularizerType.NONE));
        List<Integer> nsamples = List.of(1000, 2000, 5000);

        for (Integer nsamp : nsamples) {
            for (ShapConfig.Builder sk : testConfigs) {
                ShapKernelExplainer ske = new ShapKernelExplainer(sk.withNSamples(nsamp).build());
                SaliencyResults shapResults = ske.explainAsync(p, model).get();
                Map<String, Saliency> saliencies = shapResults.getSaliencies();
                RealMatrix[] explanationsAndConfs = saliencyToMatrix(saliencies, true);
                RealMatrix explanations = explanationsAndConfs[0];

                double actualOut = predictionOutputVector.getEntry(0);
                double predOut = MatrixUtilsExtensions.sum(explanations.getRowVector(0));
                assertTrue(Math.abs(predOut - actualOut) < 1e-6);

                double coefMSE = (data.getRowVector(100).ebeMultiply(modelWeights))
                        .getDistance(explanations.getRowVector(0).getSubVector(0, 25));
                assertTrue(coefMSE < 10);
            }
        }
    }

    @Test
    void testManyFeatureRegularization2() throws ExecutionException, InterruptedException {
        RealVector modelWeights = MatrixUtils.createRealMatrix(generateN(1, 25, "5021")).getRowVector(0);
        PredictionProvider model = TestModels.getLinearModel(modelWeights.toArray());
        RealMatrix data = MatrixUtils.createRealMatrix(generateN(101, 25, "8629"));
        List<PredictionInput> toExplain = createPIFromMatrix(data.getRowMatrix(100).getData());
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        RealVector predictionOutputVector = MatrixUtilsExtensions.vectorFromPredictionOutput(predictionOutputs.get(0));
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        List<PredictionInput> bg = createPIFromMatrix(new double[100][25]);

        ShapConfig sk = testConfig.copy()
                .withBackground(bg)
                .withRegularizer(ShapConfig.RegularizerType.AIC)
                .withNSamples(5000)
                .build();

        ShapKernelExplainer ske = new ShapKernelExplainer(sk);
        SaliencyResults shapResults = ske.explainAsync(p, model).get();
        Map<String, Saliency> saliencies = shapResults.getSaliencies();
        RealMatrix[] explanationsAndConfs = saliencyToMatrix(saliencies);
        RealMatrix explanations = explanationsAndConfs[0];

        double actualOut = predictionOutputVector.getEntry(0);
        double predOut = MatrixUtilsExtensions.sum(explanations.getRowVector(0));
        assertTrue(Math.abs(predOut - actualOut) < 1e-6);
        double coefMSE = (data.getRowVector(100).ebeMultiply(modelWeights)).getDistance(explanations.getRowVector(0));
        assertTrue(coefMSE < .01);
    }

    @Test
    void testExceedSubsetSamplerRange() throws ExecutionException, InterruptedException {
        RealVector modelWeights = MatrixUtils.createRealMatrix(generateN(1, 50, "5021")).getRowVector(0);
        PredictionProvider model = TestModels.getLinearModel(modelWeights.toArray());
        RealMatrix data = MatrixUtils.createRealMatrix(generateN(101, 50, "8629"));
        List<PredictionInput> toExplain = createPIFromMatrix(data.getRowMatrix(100).getData());
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        List<PredictionInput> bg = createPIFromMatrix(new double[100][50]);

        ShapConfig sk = testConfig.copy()
                .withBackground(bg)
                .withRegularizer(ShapConfig.RegularizerType.AIC)
                .withNSamples(2000)
                .build();

        ShapKernelExplainer ske = new ShapKernelExplainer(sk);
        SaliencyResults shapResults = ske.explainAsync(p, model).get();
        assertTrue(true);
    }

    @Test
    void testBatched() throws ExecutionException, InterruptedException {
        RealVector modelWeights = MatrixUtils.createRealMatrix(generateN(1, 10, "5021")).getRowVector(0);
        PredictionProvider model = TestModels.getLinearModel(modelWeights.toArray());
        RealMatrix data = MatrixUtils.createRealMatrix(generateN(101, 10, "8629"));
        List<PredictionInput> toExplain = createPIFromMatrix(data.getRowMatrix(100).getData());
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        List<PredictionInput> bg = createPIFromMatrix(new double[100][10]);

        ShapConfig skNB = testConfig.copy()
                .withBackground(bg)
                .withBatchCount(1)
                .build();
        ShapConfig skB = testConfig.copy()
                .withBackground(bg)
                .withBatchCount(20)
                .build();

        ShapKernelExplainer skeNB = new ShapKernelExplainer(skNB);
        SaliencyResults shapResultsNB = skeNB.explainAsync(p, model).get();
        ShapKernelExplainer skeB = new ShapKernelExplainer(skB);
        SaliencyResults shapResultsB = skeB.explainAsync(p, model).get();
        assertEquals(shapResultsNB, shapResultsB);
    }

    @Test
    void testCategorical() throws ExecutionException, InterruptedException {
        List<Value> fruits = List.of(
                new Value("avocado"),
                new Value("banana"),
                new Value("carrot"),
                new Value("dragonfruit"),
                new Value(""));
        Random rn = new Random(101);

        List<PredictionInput> data = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            List<Feature> fs = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                fs.add(new Feature(String.format("Fruit %d", j), Type.CATEGORICAL, fruits.get(rn.nextInt(5))));
            }
            data.add(new PredictionInput(fs));
        }

        List<PredictionInput> nulldata = new ArrayList<>();
        List<Feature> fs = new ArrayList<>();
        for (int j = 0; j < 8; j++) {
            fs.add(new Feature(String.format("Fruit %d", j), Type.CATEGORICAL, fruits.get(4)));
        }
        nulldata.add(new PredictionInput(fs));
        List<PredictionInput> bg = nulldata;
        List<PredictionInput> toExplain = data.subList(100, 101);
        PredictionProvider model = TestModels.getCategoricalRegressor();
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        ShapConfig sk = testConfig.copy()
                .withBackground(bg)
                .withNSamples(5000)
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(sk);
        SaliencyResults shapResults = ske.explainAsync(p, model).get();

        assertEquals(25, shapResults.getSaliencies().get("calories")
                .getPerFeatureImportance().get(0).getScore(), 1e-6);
        assertEquals(61, shapResults.getSaliencies().get("calories")
                .getPerFeatureImportance().get(1).getScore(), 1e-6);
        assertEquals(1, shapResults.getSaliencies().get("fruit_eaten")
                .getPerFeatureImportance().get(0).getScore(), 1e-6);
        assertEquals(1, shapResults.getSaliencies().get("fruit_eaten")
                .getPerFeatureImportance().get(1).getScore(), 1e-6);
    }

    @Test
    void testCategoricalCollisions() throws ExecutionException, InterruptedException {
        List<Value> fruits = List.of(
                new Value("avocado"),
                new Value("banana"),
                new Value("carrot"),
                new Value("dragonfruit"),
                new Value(""));
        Random rn = new Random(101);

        List<PredictionInput> data = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            List<Feature> fs = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                fs.add(new Feature(String.format("Fruit_OHE_%d", j), Type.CATEGORICAL, fruits.get(rn.nextInt(5))));
            }
            data.add(new PredictionInput(fs));
        }

        List<PredictionInput> nulldata = new ArrayList<>();
        List<Feature> fs = new ArrayList<>();
        for (int j = 0; j < 8; j++) {
            fs.add(new Feature(String.format("Fruit_OHE_%d", j), Type.CATEGORICAL, fruits.get(4)));
        }
        nulldata.add(new PredictionInput(fs));
        List<PredictionInput> bg = nulldata;
        List<PredictionInput> toExplain = data.subList(100, 101);
        PredictionProvider model = TestModels.getCategoricalRegressor();
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();
        Prediction p = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        ShapConfig sk = testConfig.copy()
                .withBackground(bg)
                .withNSamples(5000)
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(sk);
        SaliencyResults shapResults = ske.explainAsync(p, model).get();

        assertEquals(25, shapResults.getSaliencies().get("calories")
                .getPerFeatureImportance().get(0).getScore(), 1e-6);
        assertEquals(61, shapResults.getSaliencies().get("calories")
                .getPerFeatureImportance().get(1).getScore(), 1e-6);
        assertEquals(1, shapResults.getSaliencies().get("fruit_eaten")
                .getPerFeatureImportance().get(0).getScore(), 1e-6);
        assertEquals(1, shapResults.getSaliencies().get("fruit_eaten")
                .getPerFeatureImportance().get(1).getScore(), 1e-6);
    }

    @Test
    void testToString() throws ExecutionException, InterruptedException, TimeoutException {
        Random random = new Random(0L);

        List<PredictionInput> background = new ArrayList<>();
        for (int bg = 0; bg < 1; bg++) {
            List<Feature> features = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                if (i == 2) {
                    features.add(new Feature("Feature " + i, Type.CATEGORICAL, new Value("B")));
                } else {
                    features.add(new Feature("Feature " + i, Type.NUMBER, new Value(0)));
                }
            }
            background.add(new PredictionInput(features));
        }
        ShapConfig shapConfig = testConfig.copy()
                .withBackground(background)
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(shapConfig);

        List<Feature> features = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i == 2) {
                features.add(new Feature(
                        "Feature " + i,
                        Type.CATEGORICAL,
                        new Value("A"),
                        false,
                        CategoricalFeatureDomain.create(List.of("A", "B"))));
            } else {
                features.add(new Feature("Feature " + i, Type.NUMBER, new Value(i)));
            }
        }
        PredictionInput input = new PredictionInput(features);
        PredictionProvider model = TestModels.getTwoOutputSemiCategoricalModel(2);
        PredictionOutput output = model.predictAsync(List.of(input))
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit())
                .get(0);
        Prediction prediction = new SimplePrediction(input, output);
        SaliencyResults results = ske.explainAsync(prediction, model)
                .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        String table = results.asTable();
        assertTrue(table.contains("Semi-Categorical"));
        assertTrue(table.contains("SHAP Value"));
        assertTrue(table.contains("Feature 1"));
    }

    @Test
    void testNoByproductCounterfactuals() throws InterruptedException, ExecutionException {
        PredictionProvider model = TestModels.getSumSkipModel(1);
        List<PredictionInput> toExplain = createPIFromMatrix(toExplainRaw);
        List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();

        Prediction p0 = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
        ShapConfig skConfig = testConfig.copy()
                .withBackground(createPIFromMatrix(backgroundRaw))
                .withBatchCount(1)
                .withNSamples(1000)
                .withTrackCounterfactuals(false)
                .build();
        ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
        SaliencyResults shapResults0 = ske.explainAsync(p0, model).get();

        // check that SHAP returns the expected number of Counterfactuals
        assertEquals(0, shapResults0.getAvailableCFs().size());
    }

    @Test
    void testByproductCounterfactuals() throws InterruptedException, ExecutionException {
        // this has to be run many times to ensure there's no race condition issues
        for (long i = 0; i < 100; i++) {
            PredictionProvider model = TestModels.getSumSkipModel(1);
            List<PredictionInput> toExplain = createPIFromMatrix(toExplainRaw);
            List<PredictionOutput> predictionOutputs = model.predictAsync(toExplain).get();

            Prediction p0 = new SimplePrediction(toExplain.get(0), predictionOutputs.get(0));
            Prediction p1 = new SimplePrediction(toExplain.get(1), predictionOutputs.get(1));
            ShapConfig skConfig = testConfig.copy()
                    .withPC(new PerturbationContext(new Random(i), 0))
                    .withBackground(createPIFromMatrix(backgroundRaw))
                    .withBatchCount(1)
                    .withNSamples(1000)
                    .withTrackCounterfactuals(true)
                    .build();
            ShapKernelExplainer ske = new ShapKernelExplainer(skConfig);
            SaliencyResults shapResults0 = ske.explainAsync(p0, model).get();
            SaliencyResults shapResults1 = ske.explainAsync(p1, model).get();

            // check that SHAP returns the expected number of Counterfactuals
            assertEquals(42, shapResults0.getAvailableCFs().size());
            assertEquals(90, shapResults1.getAvailableCFs().size());

        }
    }

}
