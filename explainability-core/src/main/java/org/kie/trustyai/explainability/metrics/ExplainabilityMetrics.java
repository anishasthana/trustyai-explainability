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
package org.kie.trustyai.explainability.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.kie.trustyai.explainability.Config;
import org.kie.trustyai.explainability.local.LocalExplainer;
import org.kie.trustyai.explainability.model.DataDistribution;
import org.kie.trustyai.explainability.model.Feature;
import org.kie.trustyai.explainability.model.FeatureImportance;
import org.kie.trustyai.explainability.model.Output;
import org.kie.trustyai.explainability.model.Prediction;
import org.kie.trustyai.explainability.model.PredictionInput;
import org.kie.trustyai.explainability.model.PredictionOutput;
import org.kie.trustyai.explainability.model.PredictionProvider;
import org.kie.trustyai.explainability.model.Saliency;
import org.kie.trustyai.explainability.model.SaliencyResults;
import org.kie.trustyai.explainability.model.Type;
import org.kie.trustyai.explainability.utils.DataUtils;
import org.kie.trustyai.explainability.utils.LocalSaliencyStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.kie.trustyai.explainability.utils.DataUtils.sortPredictionsByScore;

/**
 * Utility class providing different methods to evaluate explainability.
 */
public class ExplainabilityMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExplainabilityMetrics.class);

    /**
     * Drop in confidence score threshold for impact score calculation.
     * Confidence scores below {@code originalScore * CONFIDENCE_DROP_RATIO} are considered impactful for a model.
     */
    private static final double CONFIDENCE_DROP_RATIO = 0.2d;

    private ExplainabilityMetrics() {
    }

    /**
     * Measure the explainability of an explanation.
     * See paper: "Towards Quantification of Explainability in Explainable Artificial Intelligence Methods" by Islam et al.
     *
     * @param inputCognitiveChunks the no. of cognitive chunks (pieces of information) required to generate the
     *        explanation (e.g. the no. of explanation inputs)
     * @param outputCognitiveChunks the no. of cognitive chunks generated within the explanation itself
     * @param interactionRatio the ratio of interaction (between 0 and 1) required by the explanation
     * @return the quantitative explainability measure
     */
    public static double quantifyExplainability(int inputCognitiveChunks, int outputCognitiveChunks, double interactionRatio) {
        return inputCognitiveChunks + outputCognitiveChunks > 0 ? 0.333 / (double) inputCognitiveChunks
                + 0.333 / (double) outputCognitiveChunks + 0.333 * (1d - interactionRatio) : 0;
    }

    /**
     * Calculate the impact of dropping the most important features (given by {@link Saliency#getTopFeatures(int)} from the input.
     * Highly important features would have rather high impact.
     * See paper: Qiu Lin, Zhong, et al. "Do Explanations Reflect Decisions? A Machine-centric Strategy to Quantify the
     * Performance of Explainability Algorithms." 2019.
     *
     * @param model the model to be explained
     * @param prediction a prediction
     * @param topFeatures the list of important features that should be dropped
     * @return the saliency impact
     */
    public static double impactScore(PredictionProvider model, Prediction prediction, List<FeatureImportance> topFeatures) throws InterruptedException, ExecutionException, TimeoutException {
        List<Feature> copy = List.copyOf(prediction.getInput().getFeatures());
        for (FeatureImportance featureImportance : topFeatures) {
            copy = DataUtils.dropFeature(copy, featureImportance.getFeature());
        }

        PredictionInput predictionInput = new PredictionInput(copy);
        List<PredictionOutput> predictionOutputs;
        try {
            predictionOutputs = model.predictAsync(List.of(predictionInput))
                    .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.error("Impossible to obtain prediction {}", e.getMessage());
            throw new IllegalStateException("Impossible to obtain prediction", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Impossible to obtain prediction (Thread interrupted)", e);
        }
        double impact = 0d;
        for (PredictionOutput predictionOutput : predictionOutputs) {
            double size = predictionOutput.getOutputs().size();
            for (int i = 0; i < size; i++) {
                Output original = prediction.getOutput().getOutputs().get(i);
                Output modified = predictionOutput.getOutputs().get(i);
                impact += (!original.getValue().asString().equals(modified.getValue().asString())
                        || modified.getScore() < original.getScore() * CONFIDENCE_DROP_RATIO) ? 1d / size : 0d;
            }
        }
        return impact;
    }

    /**
     * Calculate fidelity (accuracy) of boolean classification outputs using saliency predictor function = sign(sum(saliency.scores))
     * See papers:
     * - Guidotti Riccardo, et al. "A survey of methods for explaining black box models." ACM computing surveys (2018).
     * - Bodria, Francesco, et al. "Explainability Methods for Natural Language Processing: Applications to Sentiment Analysis (Discussion Paper)."
     *
     * @param pairs pairs composed by the saliency and the related prediction
     * @return the fidelity accuracy
     */
    public static double classificationFidelity(List<Pair<Saliency, Prediction>> pairs) {
        double acc = 0;
        double evals = 0;
        for (Pair<Saliency, Prediction> pair : pairs) {
            Saliency saliency = pair.getLeft();
            Prediction prediction = pair.getRight();
            for (Output output : prediction.getOutput().getOutputs()) {
                Type type = output.getType();
                if (Type.BOOLEAN.equals(type)) {
                    double predictorOutput = saliency.getPerFeatureImportance().stream().map(FeatureImportance::getScore).mapToDouble(d -> d).sum();
                    double v = output.getValue().asNumber();
                    if ((v >= 0 && predictorOutput >= 0) || (v < 0 && predictorOutput < 0)) {
                        acc++;
                    }
                    evals++;
                }
            }
        }
        return evals == 0 ? 0 : acc / evals;
    }

    /**
     * Evaluate stability of a local explainer generating {@code Saliencies}.
     * Such an evaluation is intended to measure how stable the explanations are in terms of "are the top k most important
     * positive/negative features always the same for a single prediction?".
     *
     * @param model a model to explain
     * @param prediction the prediction on which explanation stability will be evaluated
     * @param saliencyLocalExplainer a local saliency explainer
     * @param topK no. of top k positive/negative features for which stability report will be generated
     * @param runs no. of times the saliency for each prediction needs to be generated
     * @return a report about stability of all the decisions/predictions (and for each {@code k < topK})
     */
    public static LocalSaliencyStability getLocalSaliencyStability(PredictionProvider model, Prediction prediction,
            LocalExplainer<SaliencyResults> saliencyLocalExplainer,
            int topK, int runs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Map<String, List<Saliency>> saliencies = getMultipleSaliencies(model, prediction, saliencyLocalExplainer, runs);

        LocalSaliencyStability saliencyStability = new LocalSaliencyStability(saliencies.keySet());
        // for each decision, calculate the stability rate for the top k important feature set, for each k < topK
        for (Map.Entry<String, List<Saliency>> entry : saliencies.entrySet()) {
            String decision = entry.getKey();
            List<Saliency> perDecisionSaliencies = entry.getValue();
            for (int k = 1; k <= topK; k++) {
                int finalK = k;
                // get the top k positive features list from each saliency and count the frequency of each such list across all saliencies
                Map<List<String>, Long> topKPositive = getTopKFeaturesFrequency(perDecisionSaliencies, s -> s.getPositiveFeatures(finalK));
                // get the most frequent list of positive features
                Pair<List<String>, Long> positiveMostFrequent = getMostFrequent(topKPositive);
                double positiveFrequencyRate = (double) positiveMostFrequent.getValue() / (double) perDecisionSaliencies.size();

                // get the top k negative features list from each saliency and count the frequency of each such list across all saliencies
                Map<List<String>, Long> topKNegative = getTopKFeaturesFrequency(perDecisionSaliencies, s -> s.getNegativeFeatures(finalK));
                // get the most frequent list of negative features
                Pair<List<String>, Long> negativeMostFrequent = getMostFrequent(topKNegative);
                double negativeFrequencyRate = (double) negativeMostFrequent.getValue() / (double) perDecisionSaliencies.size();

                // decision stability at k
                List<String> positiveFeatureNames = positiveMostFrequent.getKey();
                List<String> negativeFeatureNames = negativeMostFrequent.getKey();
                saliencyStability.add(decision, k, positiveFeatureNames, positiveFrequencyRate, negativeFeatureNames, negativeFrequencyRate);
            }
        }
        return saliencyStability;
    }

    /**
     * Get multiple saliencies, aggregated by decision name.
     *
     * @param model the model used to perform predictions
     * @param prediction the prediction to explain
     * @param saliencyLocalExplainer a local explainer that generates saliences
     * @param runs the no. of explanations to be generated
     * @return the generated saliencies, aggregated by decision name, across the different runs
     */
    private static Map<String, List<Saliency>> getMultipleSaliencies(PredictionProvider model, Prediction prediction,
            LocalExplainer<SaliencyResults> saliencyLocalExplainer,
            int runs)
            throws InterruptedException, ExecutionException, TimeoutException {
        Map<String, List<Saliency>> saliencies = new HashMap<>();
        int skipped = 0;
        for (int i = 0; i < runs; i++) {
            SaliencyResults saliencyMap = saliencyLocalExplainer.explainAsync(prediction, model)
                    .get(Config.INSTANCE.getAsyncTimeout(), Config.INSTANCE.getAsyncTimeUnit());

            for (Map.Entry<String, Saliency> saliencyEntry : saliencyMap.getSaliencies().entrySet()) {
                // aggregate saliencies by output name
                List<FeatureImportance> topFeatures = saliencyEntry.getValue().getTopFeatures(1);

                if (!topFeatures.isEmpty() && topFeatures.get(0).getScore() != 0) { // skip empty or 0 valued saliencies
                    if (saliencies.containsKey(saliencyEntry.getKey())) {
                        List<Saliency> localSaliencies = saliencies.get(saliencyEntry.getKey());
                        List<Saliency> updatedSaliencies = new ArrayList<>(localSaliencies);
                        updatedSaliencies.add(saliencyEntry.getValue());
                        saliencies.put(saliencyEntry.getKey(), updatedSaliencies);
                    } else {
                        saliencies.put(saliencyEntry.getKey(), List.of(saliencyEntry.getValue()));
                    }
                } else {
                    LOGGER.debug("skipping empty / zero saliency for {}", saliencyEntry.getKey());
                    skipped++;
                }
            }
        }
        LOGGER.debug("skipped {} useless saliencies", skipped);
        return saliencies;
    }

    private static Map<List<String>, Long> getTopKFeaturesFrequency(List<Saliency> saliencies, Function<Saliency, List<FeatureImportance>> saliencyListFunction) {
        return saliencies.stream().map(saliencyListFunction)
                .map(l -> l.stream().map(f -> f.getFeature().getName())
                        .collect(Collectors.toList()))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static Pair<List<String>, Long> getMostFrequent(Map<List<String>, Long> collect) {
        Map.Entry<List<String>, Long> maxEntry = Collections.max(collect.entrySet(), Map.Entry.comparingByValue());
        return Pair.of(maxEntry.getKey(), maxEntry.getValue());
    }

    /**
     * Evaluate the recall of a local saliency explainer on a given model.
     * Get the predictions having outputs with the highest score for the given decision and pair them with predictions
     * whose outputs have the lowest score for the same decision.
     * Get the top k (most important) features (according to the saliency) for the most important outputs and
     * "paste" them on each paired input corresponding to an output with low score (for the target decision).
     * Perform prediction on the "masked" input, if the output on the masked input is equals to the output for the
     * input the mask features were take from, that's considered a true positive, otherwise it's a false positive.
     * see Section 3.2.1 of https://openreview.net/attachment?id=B1xBAA4FwH&name=original_pdf
     *
     * @param outputName decision to evaluate recall for
     * @param predictionProvider the prediction provider to test
     * @param localExplainer the explainer to evaluate
     * @param dataDistribution the data distribution used to obtain inputs for evaluation
     * @param k the no. of features to extract
     * @param chunkSize the size of the chunk of predictions to use for evaluation
     * @return the saliency recall
     */
    public static double getLocalSaliencyRecall(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer,
            DataDistribution dataDistribution, int k, int chunkSize)
            throws InterruptedException, ExecutionException, TimeoutException {

        // get all samples from the data distribution
        List<Prediction> sorted = DataUtils.getScoreSortedPredictions(outputName, predictionProvider, dataDistribution);

        return calculateLocalSaliencyRecall(outputName, predictionProvider, localExplainer, k, chunkSize, sorted);
    }

    /**
     * Evaluate the recall of a local saliency explainer on a given model.
     * Get the predictions having outputs with the highest score for the given decision and pair them with predictions
     * whose outputs have the lowest score for the same decision.
     * Get the top k (most important) features (according to the saliency) for the most important outputs and
     * "paste" them on each paired input corresponding to an output with low score (for the target decision).
     * Perform prediction on the "masked" input, if the output on the masked input is equals to the output for the
     * input the mask features were take from, that's considered a true positive, otherwise it's a false positive.
     * see Section 3.2.1 of https://openreview.net/attachment?id=B1xBAA4FwH&name=original_pdf
     *
     * @param outputName decision to evaluate recall for
     * @param predictionProvider the prediction provider to test
     * @param localExplainer the explainer to evaluate
     * @param predictions a list of predictions used to calculate the recall
     * @param k the no. of features to extract
     * @param chunkSize the size of the chunk of predictions to use for evaluation
     * @return the saliency recall
     */
    public static double getLocalSaliencyRecall(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer,
            List<Prediction> predictions, int k, int chunkSize)
            throws InterruptedException, ExecutionException, TimeoutException {

        return calculateLocalSaliencyRecall(outputName, predictionProvider, localExplainer, k, chunkSize,
                sortPredictionsByScore(outputName, predictions));
    }

    private static double calculateLocalSaliencyRecall(String outputName, PredictionProvider predictionProvider, LocalExplainer<SaliencyResults> localExplainer, int k, int chunkSize,
            List<Prediction> sorted) throws InterruptedException, ExecutionException, TimeoutException {
        // get the top and bottom 'chunkSize' predictions
        List<Prediction> topChunk = new ArrayList<>(sorted.subList(0, chunkSize));
        List<Prediction> bottomChunk = new ArrayList<>(sorted.subList(sorted.size() - chunkSize, sorted.size()));

        double truePositives = 0;
        double falseNegatives = 0;
        int currentChunk = 0;
        // for each of the top scored predictions, get the top influencing features and copy them over a low scored
        // input, then feed the model with this masked input and check the output is equals to the top scored one.
        for (Prediction prediction : topChunk) {
            Optional<Output> optionalOutput = prediction.getOutput().getByName(outputName);
            if (optionalOutput.isPresent()) {
                Output output = optionalOutput.get();
                SaliencyResults stringSaliencyMap = localExplainer.explainAsync(prediction, predictionProvider)
                        .get(Config.DEFAULT_ASYNC_TIMEOUT * 2, Config.DEFAULT_ASYNC_TIMEUNIT);
                if (stringSaliencyMap.getSaliencies().containsKey(outputName)) {
                    Saliency saliency = stringSaliencyMap.getSaliencies().get(outputName);
                    List<FeatureImportance> topFeatures = saliency.getPerFeatureImportance().stream()
                            .sorted((f1, f2) -> Double.compare(f2.getScore(), f1.getScore())).limit(k).collect(Collectors.toList());

                    PredictionInput input = bottomChunk.get(currentChunk).getInput();
                    PredictionInput maskedInput = maskInput(topFeatures, input);

                    List<PredictionOutput> predictionOutputList = predictionProvider.predictAsync(List.of(maskedInput))
                            .get(Config.DEFAULT_ASYNC_TIMEOUT, Config.DEFAULT_ASYNC_TIMEUNIT);
                    if (!predictionOutputList.isEmpty()) {
                        PredictionOutput predictionOutput = predictionOutputList.get(0);
                        Optional<Output> optionalNewOutput = predictionOutput.getByName(outputName);
                        if (optionalNewOutput.isPresent()) {
                            Output newOutput = optionalOutput.get();
                            if (output.getValue().equals(newOutput.getValue())) {
                                truePositives++;
                            } else {
                                falseNegatives++;
                            }
                        }
                    }
                    currentChunk++;
                }
            }
        }
        if ((truePositives + falseNegatives) > 0) {
            return truePositives / (truePositives + falseNegatives);
        } else {
            // if topChunk is empty or the target output (by name) is not an output of the model.
            return Double.NaN;
        }
    }

    private static PredictionInput maskInput(List<FeatureImportance> topFeatures, PredictionInput input) {
        List<Feature> importantFeatures = new ArrayList<>();
        for (FeatureImportance featureImportance : topFeatures) {
            importantFeatures.add(featureImportance.getFeature());
        }

        return replaceAllFeatures(importantFeatures, input);
    }

    /**
     * Evaluate the precision of a local saliency explainer on a given model.
     * Get the predictions having outputs with the lowest score for the given decision and pair them with predictions
     * whose outputs have the highest score for the same decision.
     * Get the bottom k (less important) features (according to the saliency) for the less important outputs and
     * "paste" them on each paired input corresponding to an output with high score (for the target decision).
     * Perform prediction on the "masked" input, if the output changes that's considered a false negative, otherwise
     * it's a true positive.
     * see Section 3.2.1 of https://openreview.net/attachment?id=B1xBAA4FwH&name=original_pdf
     *
     * @param outputName decision to evaluate recall for
     * @param predictionProvider the prediction provider to test
     * @param localExplainer the explainer to evaluate
     * @param dataDistribution the data distribution used to obtain inputs for evaluation
     * @param k the no. of features to extract
     * @param chunkSize the size of the chunk of predictions to use for evaluation
     * @return the saliency precision
     */
    public static double getLocalSaliencyPrecision(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer,
            DataDistribution dataDistribution, int k, int chunkSize)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<Prediction> sorted = DataUtils.getScoreSortedPredictions(outputName, predictionProvider, dataDistribution);

        return calculateLocalSaliencyPrecision(outputName, predictionProvider, localExplainer, k, chunkSize, sorted);
    }

    private static double calculateLocalSaliencyPrecision(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer, int k, int chunkSize,
            List<Prediction> sorted) throws InterruptedException, ExecutionException, TimeoutException {
        // get the top and bottom 'chunkSize' predictions
        List<Prediction> topChunk = new ArrayList<>(sorted.subList(0, chunkSize));
        List<Prediction> bottomChunk = new ArrayList<>(sorted.subList(sorted.size() - chunkSize, sorted.size()));

        double truePositives = 0;
        double falsePositives = 0;
        int currentChunk = 0;

        for (Prediction prediction : bottomChunk) {
            SaliencyResults stringSaliencyMap = localExplainer.explainAsync(prediction, predictionProvider)
                    .get(Config.DEFAULT_ASYNC_TIMEOUT * 2, Config.DEFAULT_ASYNC_TIMEUNIT);
            if (stringSaliencyMap.getSaliencies().containsKey(outputName)) {
                Saliency saliency = stringSaliencyMap.getSaliencies().get(outputName);
                List<FeatureImportance> topFeatures = saliency.getPerFeatureImportance().stream()
                        .sorted(Comparator.comparingDouble(FeatureImportance::getScore)).limit(k).collect(Collectors.toList());

                Prediction topPrediction = topChunk.get(currentChunk);
                PredictionInput input = topPrediction.getInput();
                PredictionInput maskedInput = maskInput(topFeatures, input);

                List<PredictionOutput> predictionOutputList = predictionProvider.predictAsync(List.of(maskedInput))
                        .get(Config.DEFAULT_ASYNC_TIMEOUT, Config.DEFAULT_ASYNC_TIMEUNIT);
                if (!predictionOutputList.isEmpty()) {
                    PredictionOutput predictionOutput = predictionOutputList.get(0);
                    Optional<Output> newOptionalOutput = predictionOutput.getByName(outputName);
                    if (newOptionalOutput.isPresent()) {
                        Output newOutput = newOptionalOutput.get();
                        Optional<Output> optionalOutput = topPrediction.getOutput().getByName(outputName);
                        if (optionalOutput.isPresent()) {
                            Output output = optionalOutput.get();
                            if (output.getValue().equals(newOutput.getValue())) {
                                truePositives++;
                            } else {
                                falsePositives++;
                            }
                        }
                    }
                }
                currentChunk++;
            }
        }
        if ((truePositives + falsePositives) > 0) {
            return truePositives / (truePositives + falsePositives);
        } else {
            // if bottomChunk is empty or the target output (by name) is not an output of the model.
            return Double.NaN;
        }
    }

    /**
     * Evaluate the precision of a local saliency explainer on a given model.
     * Get the predictions having outputs with the lowest score for the given decision and pair them with predictions
     * whose outputs have the highest score for the same decision.
     * Get the bottom k (less important) features (according to the saliency) for the less important outputs and
     * "paste" them on each paired input corresponding to an output with high score (for the target decision).
     * Perform prediction on the "masked" input, if the output changes that's considered a false negative, otherwise
     * it's a true positive.
     * see Section 3.2.1 of https://openreview.net/attachment?id=B1xBAA4FwH&name=original_pdf
     *
     * @param outputName decision to evaluate recall for
     * @param predictionProvider the prediction provider to test
     * @param localExplainer the explainer to evaluate
     * @param predictions a list of predictions used to calculate the precision
     * @param k the no. of features to extract
     * @param chunkSize the size of the chunk of predictions to use for evaluation
     * @return the saliency precision
     */
    public static double getLocalSaliencyPrecision(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer,
            List<Prediction> predictions, int k, int chunkSize)
            throws ExecutionException, InterruptedException, TimeoutException {
        return calculateLocalSaliencyPrecision(outputName, predictionProvider, localExplainer, k, chunkSize,
                sortPredictionsByScore(outputName, predictions));
    }

    /**
     * Get local saliency F1 score.
     * <p>
     * see <a href="https://en.wikipedia.org/wiki/F-score"/>
     * See {@link #getLocalSaliencyPrecision(String, PredictionProvider, LocalExplainer, DataDistribution, int, int)}
     * See {@link #getLocalSaliencyRecall(String, PredictionProvider, LocalExplainer, DataDistribution, int, int)}
     *
     * @param outputName decision to evaluate recall for
     * @param predictionProvider the prediction provider to test
     * @param localExplainer the explainer to evaluate
     * @param dataDistribution the data distribution used to obtain inputs for evaluation
     * @param k the no. of features to extract
     * @param chunkSize the size of the chunk of predictions to use for evaluation
     * @return the saliency F1
     */
    public static double getLocalSaliencyF1(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer,
            DataDistribution dataDistribution, int k, int chunkSize)
            throws InterruptedException, ExecutionException, TimeoutException {
        double precision = getLocalSaliencyPrecision(outputName, predictionProvider, localExplainer, dataDistribution, k, chunkSize);
        double recall = getLocalSaliencyRecall(outputName, predictionProvider, localExplainer, dataDistribution, k, chunkSize);
        if (Double.isFinite(precision + recall) && (precision + recall) > 0) {
            return 2 * precision * recall / (precision + recall);
        } else {
            return Double.NaN;
        }
    }

    /**
     * Get local saliency F1 score.
     * <p>
     * see <a href="https://en.wikipedia.org/wiki/F-score"/>
     * See {@link #getLocalSaliencyPrecision(String, PredictionProvider, LocalExplainer, DataDistribution, int, int)}
     * See {@link #getLocalSaliencyRecall(String, PredictionProvider, LocalExplainer, DataDistribution, int, int)}
     *
     * @param outputName decision to evaluate recall for
     * @param predictionProvider the prediction provider to test
     * @param localExplainer the explainer to evaluate
     * @param predictions a list of predictions
     * @param k the no. of features to extract
     * @param chunkSize the size of the chunk of predictions to use for evaluation
     * @return the saliency F1
     */
    public static double getLocalSaliencyF1(String outputName, PredictionProvider predictionProvider,
            LocalExplainer<SaliencyResults> localExplainer,
            List<Prediction> predictions, int k, int chunkSize)
            throws InterruptedException, ExecutionException, TimeoutException {
        double precision = getLocalSaliencyPrecision(outputName, predictionProvider, localExplainer, predictions, k, chunkSize);
        double recall = getLocalSaliencyRecall(outputName, predictionProvider, localExplainer, predictions, k, chunkSize);
        if (Double.isFinite(precision + recall) && (precision + recall) > 0) {
            return 2 * precision * recall / (precision + recall);
        } else {
            return Double.NaN;
        }
    }

    private static PredictionInput replaceAllFeatures(List<Feature> importantFeatures, PredictionInput input) {
        List<Feature> features = List.copyOf(input.getFeatures());
        for (Feature f : importantFeatures) {
            features = DataUtils.replaceFeatures(f, features);
        }
        return new PredictionInput(features);
    }
}
