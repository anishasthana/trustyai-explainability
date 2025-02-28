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
package org.kie.trustyai.explainability.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.tuple.Pair;
import org.kie.trustyai.explainability.Config;
import org.kie.trustyai.explainability.local.lime.HighScoreNumericFeatureZones;
import org.kie.trustyai.explainability.model.DataDistribution;
import org.kie.trustyai.explainability.model.Feature;
import org.kie.trustyai.explainability.model.FeatureDistribution;
import org.kie.trustyai.explainability.model.FeatureFactory;
import org.kie.trustyai.explainability.model.IndependentFeaturesDataDistribution;
import org.kie.trustyai.explainability.model.NumericFeatureDistribution;
import org.kie.trustyai.explainability.model.Output;
import org.kie.trustyai.explainability.model.PartialDependenceGraph;
import org.kie.trustyai.explainability.model.PerturbationContext;
import org.kie.trustyai.explainability.model.Prediction;
import org.kie.trustyai.explainability.model.PredictionInput;
import org.kie.trustyai.explainability.model.PredictionInputsDataDistribution;
import org.kie.trustyai.explainability.model.PredictionOutput;
import org.kie.trustyai.explainability.model.PredictionProvider;
import org.kie.trustyai.explainability.model.SimplePrediction;
import org.kie.trustyai.explainability.model.Type;
import org.kie.trustyai.explainability.model.Value;

/**
 * Utility methods to handle and manipulate data.
 */
public class DataUtils {

    private DataUtils() {
    }

    /**
     * Generate a dataset of a certain size, sampled from a normal distribution, given mean and standard deviation.
     * Samples are generated from a normal distribution, multiplied by {@code stdDeviation} and summed to {@code mean},
     * actual mean {@code m} and standard deviation {@code d} are calculated.
     * Then all numbers are multiplied by the same number so that the standard deviation also gets
     * multiplied by the same number, hence we multiply each random number by {@code stdDeviation / d}.
     * The resultant set has standard deviation {@code stdDeviation} and mean {@code m1=m*stdDeviation/d}.
     * If a same number is added to all values the mean also changes by the same number so we add {@code mean - m1} to
     * all numbers.
     *
     * @param mean desired mean
     * @param stdDeviation desired standard deviation
     * @param size size of the array
     * @return the generated data
     */
    public static double[] generateData(double mean, double stdDeviation, int size, Random random) {

        // generate random data from a normal (gaussian) distribution
        double[] data = new double[size];
        for (int i = 0; i < size; i++) {
            data[i] = random.nextGaussian() * stdDeviation + mean;
        }

        double generatedDataMean = getMean(data);
        double generatedDataStdDev = getStdDev(data, generatedDataMean);

        // force desired standard deviation
        double newStdDeviation = generatedDataStdDev != 0 ? stdDeviation / generatedDataStdDev : stdDeviation; // avoid division by zero
        for (int i = 0; i < size; i++) {
            data[i] *= newStdDeviation;
        }

        // get the new mean
        double newMean = generatedDataStdDev != 0 ? generatedDataMean * stdDeviation / generatedDataStdDev
                : generatedDataMean * stdDeviation;

        // force desired mean
        for (int i = 0; i < size; i++) {
            data[i] += mean - newMean;
        }

        return data;
    }

    public static double getMean(double[] data) {
        double m = 0;
        for (double datum : data) {
            m += datum;
        }
        m = m / data.length;
        return m;
    }

    public static double getStdDev(double[] data, double mean) {
        double d = 0;
        for (double datum : data) {
            d += Math.pow(datum - mean, 2);
        }
        d /= data.length;
        d = Math.sqrt(d);
        return d;
    }

    /**
     * Generate equally {@code size} sampled values between {@code min} and {@code max}.
     *
     * @param min minimum value
     * @param max maximum value
     * @param size dataset size
     * @return the generated data
     */
    public static double[] generateSamples(double min, double max, int size) {
        double[] data = new double[size];
        double val = min;
        double sum = max / size;
        for (int i = 0; i < size; i++) {
            data[i] = val;
            val += sum;
        }
        return data;
    }

    /**
     * Transform an array of double into a list of numerical features.
     *
     * @param inputs an array of double numbers
     * @return a list of numerical features
     */
    public static List<Feature> doublesToFeatures(double[] inputs) {
        return DoubleStream.of(inputs).mapToObj(DataUtils::doubleToFeature).collect(Collectors.toList());
    }

    /**
     * Transform a double into a numerical feature.
     *
     * @param d the double value
     * @return a numerical feature
     */
    static Feature doubleToFeature(double d) {
        return FeatureFactory.newNumericalFeature(String.valueOf(d), d);
    }

    /**
     * Perform perturbations on a fixed number of features in the given input.
     * Which feature will be perturbed is non deterministic.
     *
     * @param originalFeatures the input features that need to be perturbed
     * @param perturbationContext the perturbation context
     * @return a perturbed copy of the input features and a boolean array of feature preservation (true)/perturbation (false)
     */
    public static List<Feature> perturbFeatures(List<Feature> originalFeatures, PerturbationContext perturbationContext) {
        return perturbFeatures(originalFeatures, perturbationContext, Collections.emptyMap());
    }

    /**
     * Perform perturbations on a fixed number of features in the given input.
     * A map of feature distributions to draw (all, none or some of them) is given.
     * Which feature will be perturbed is non deterministic.
     *
     * @param originalFeatures the input features that need to be perturbed
     * @param perturbationContext the perturbation context
     * @param featureDistributionsMap the map of feature distributions
     * @return perturbed copy of the input features
     */
    public static List<Feature> perturbFeatures(List<Feature> originalFeatures, PerturbationContext perturbationContext,
            Map<String, FeatureDistribution> featureDistributionsMap) {
        return perturbFeaturesWithPreservationMask(originalFeatures, perturbationContext, featureDistributionsMap).getLeft();
    }

    /**
     * Perform perturbations on a fixed number of features in the given input.
     * A map of feature distributions to draw (all, none or some of them) is given.
     * Which feature will be perturbed is non deterministic.
     *
     * @param originalFeatures the input features that need to be perturbed
     * @param perturbationContext the perturbation context
     * @param featureDistributionsMap the map of feature distributions
     * @return pair, perturbed copy of the input features and boolean array of whether the feature is original (true)
     *         or perturbed (false)
     */
    public static Pair<List<Feature>, boolean[]> perturbFeaturesWithPreservationMask(List<Feature> originalFeatures, PerturbationContext perturbationContext,
            Map<String, FeatureDistribution> featureDistributionsMap) {
        List<Feature> newFeatures = new ArrayList<>(originalFeatures);
        boolean[] originalMarker = new boolean[originalFeatures.size()];
        Arrays.fill(originalMarker, true);

        if (!newFeatures.isEmpty()) {
            // perturb at most in the range [|features|/2), noOfPerturbations]
            int lowerBound = (int) Math.min(perturbationContext.getNoOfPerturbations(), 0.5d * newFeatures.size());
            int upperBound = (int) Math.max(perturbationContext.getNoOfPerturbations(), 0.5d * newFeatures.size());
            upperBound = Math.min(upperBound, newFeatures.size());
            lowerBound = Math.max(1, lowerBound); // lower bound should always be greater than zero (not ok to not perturb)
            int perturbationSize = 0;
            if (lowerBound == upperBound) {
                perturbationSize = lowerBound;
            } else if (upperBound > lowerBound) {
                perturbationSize = perturbationContext.getRandom().ints(1, lowerBound, 1 + upperBound).findFirst().orElse(1);
            }
            if (perturbationSize > 0) {
                int[] indexesToBePerturbed = perturbationContext.getRandom().ints(0, newFeatures.size())
                        .distinct().limit(perturbationSize).toArray();
                for (int index : indexesToBePerturbed) {
                    Feature feature = newFeatures.get(index);
                    Value newValue;
                    if (featureDistributionsMap.containsKey(feature.getName())) {
                        newValue = featureDistributionsMap.get(feature.getName()).sample();
                    } else {
                        newValue = feature.getType().perturb(feature.getValue(), perturbationContext);
                    }
                    Feature perturbedFeature =
                            FeatureFactory.copyOf(feature, newValue);
                    originalMarker[index] = false;
                    newFeatures.set(index, perturbedFeature);
                }
            }
        }
        return Pair.of(newFeatures, originalMarker);
    }

    /**
     * Drop a given feature from a list of existing features.
     *
     * @param features the existing features
     * @param target the feature to drop
     * @return a new list of features having the target feature dropped
     */
    public static List<Feature> dropFeature(List<Feature> features, Feature target) {
        List<Feature> newList = new ArrayList<>(features.size());
        for (Feature sourceFeature : features) {
            String sourceFeatureName = sourceFeature.getName();
            Type sourceFeatureType = sourceFeature.getType();
            Value sourceFeatureValue = sourceFeature.getValue();
            Feature f;
            if (target.getName().equals(sourceFeatureName)) {
                if (target.getType().equals(sourceFeatureType) && target.getValue().equals(sourceFeatureValue)) {
                    Value droppedValue = sourceFeatureType.drop(sourceFeatureValue);
                    f = FeatureFactory.copyOf(sourceFeature, droppedValue);
                } else {
                    f = dropOnLinearizedFeatures(target, sourceFeature);
                }
            } else if (Type.COMPOSITE.equals(sourceFeatureType)) {
                List<Feature> nestedFeatures = (List<Feature>) sourceFeatureValue.getUnderlyingObject();
                f = FeatureFactory.newCompositeFeature(sourceFeatureName, dropFeature(nestedFeatures, target));
            } else {
                // not found
                f = FeatureFactory.copyOf(sourceFeature, sourceFeatureValue);
            }
            newList.add(f);
        }

        return newList;
    }

    /**
     * Drop a target feature from a "linearized" version of a source feature.
     * Any of such linearized features are eventually dropped if they match on associated name, type and value.
     *
     * @param target the target feature
     * @param sourceFeature the source feature
     * @return the source feature having one of its underlying "linearized" values eventually dropped
     */
    protected static Feature dropOnLinearizedFeatures(Feature target, Feature sourceFeature) {
        Feature f = null;
        List<Feature> linearizedFeatures = DataUtils.getLinearizedFeatures(List.of(sourceFeature));
        int i = 0;
        for (Feature linearizedFeature : linearizedFeatures) {
            if (target.getValue().equals(linearizedFeature.getValue())) {
                linearizedFeatures.set(i,
                        FeatureFactory.copyOf(linearizedFeature, linearizedFeature.getType().drop(target.getValue())));
                f = FeatureFactory.newCompositeFeature(target.getName(), linearizedFeatures);
                break;
            } else {
                i++;
            }
        }
        // not found
        if (f == null) {
            f = FeatureFactory.copyOf(sourceFeature, sourceFeature.getValue());
        }
        return f;
    }

    /**
     * Calculate the Hamming distance between two points.
     * <p>
     * see https://en.wikipedia.org/wiki/Hamming_distance
     *
     * @param x first point
     * @param y second point
     * @return the Hamming distance
     */
    public static double hammingDistance(double[] x, double[] y) {
        if (x.length != y.length) {
            return Double.NaN;
        } else {
            double h = 0d;
            for (int i = 0; i < x.length; i++) {
                if (x[i] != y[i]) {
                    h++;
                }
            }
            return h;
        }
    }

    /**
     * Calculate the Hamming distance between two text strings.
     * <p>
     * see https://en.wikipedia.org/wiki/Hamming_distance
     *
     * @param x first string
     * @param y second string
     * @return the Hamming distance
     */
    public static double hammingDistance(String x, String y) {
        if (x.length() != y.length()) {
            List<String> xTokens = Arrays.stream(x.split(" ")).collect(Collectors.toList());
            List<String> yTokens = Arrays.stream(y.split(" ")).collect(Collectors.toList());
            double h = 0;
            for (int i = 0; i < xTokens.size(); i++) {
                if (yTokens.size() >= i && !xTokens.get(i).equals(yTokens.get(i))) {
                    h++;
                }
            }
            return h + Math.abs(xTokens.size() - yTokens.size());
        } else {
            double h = 0;
            for (int i = 0; i < x.length(); i++) {
                if (x.charAt(i) != y.charAt(i)) {
                    h++;
                }
            }
            return h;
        }
    }

    /**
     * Calculate the Jaccard distance between two text strings.
     * <p>
     * see https://en.wikipedia.org/wiki/Jaccard_index
     *
     * @param x first string
     * @param y second string
     * @return the Jaccard distance
     */
    public static double jaccardDistance(String x, String y) {
        Set<String> xTokens = Arrays.stream(x.split(" ")).collect(Collectors.toSet());
        Set<String> yTokens = Arrays.stream(y.split(" ")).collect(Collectors.toSet());
        Set<String> intersection = new HashSet<>(xTokens);
        intersection.retainAll(yTokens);
        Set<String> union = new HashSet<>(xTokens);
        xTokens.addAll(yTokens);
        return 1 - (double) intersection.size() / (double) union.size();
    }

    /**
     * Calculate the Euclidean distance between two points.
     *
     * @param x first point
     * @param y second point
     * @return the Euclidean distance
     */
    public static double euclideanDistance(double[] x, double[] y) {
        if (x.length != y.length) {
            return Double.NaN;
        } else {
            double e = 0;
            for (int i = 0; i < x.length; i++) {
                e += Math.pow(x[i] - y[i], 2);
            }
            return Math.sqrt(e);
        }
    }

    /**
     * Calculate the Gaussian kernel of a given value.
     *
     * @param x Gaussian kernel input value
     * @param mu mean
     * @param sigma variance
     * @return the Gaussian filtered value
     */
    public static double gaussianKernel(double x, double mu, double sigma) {
        return Math.exp(-Math.pow((x - mu) / sigma, 2) / 2) / (sigma * Math.sqrt(2d * Math.PI));
    }

    /**
     * Calculate exponentially smoothed kernel of a given value (e.g. distance between two points).
     *
     * @param x value to smooth
     * @param width kernel width
     * @return the exponentially smoothed value
     */
    public static double exponentialSmoothingKernel(double x, double width) {
        return Math.sqrt(Math.exp(-(Math.pow(x, 2)) / Math.pow(width, 2)));
    }

    /**
     * Generate a random data distribution.
     *
     * @param noOfFeatures number of features
     * @param distributionSize number of samples for each feature
     * @return a data distribution
     */
    public static DataDistribution generateRandomDataDistribution(int noOfFeatures, int distributionSize, Random random) {
        List<FeatureDistribution> featureDistributions = new ArrayList<>();
        for (int i = 0; i < noOfFeatures; i++) {
            double[] doubles = generateData(random.nextDouble(), random.nextDouble(), distributionSize, random);
            Feature feature = FeatureFactory.newNumericalFeature("f_" + i, Double.NaN);
            FeatureDistribution featureDistribution = new NumericFeatureDistribution(feature, doubles);
            featureDistributions.add(featureDistribution);
        }
        return new IndependentFeaturesDataDistribution(featureDistributions);
    }

    /**
     * Transform a list of prediction inputs into another list of the same prediction inputs but having linearized features.
     *
     * @param predictionInputs a list of prediction inputs
     * @return a list of prediction inputs with linearized features
     */
    public static List<PredictionInput> linearizeInputs(List<PredictionInput> predictionInputs) {
        List<PredictionInput> newInputs = new ArrayList<>();
        for (PredictionInput predictionInput : predictionInputs) {
            newInputs.add(new PredictionInput(getLinearizedFeatures(predictionInput.getFeatures())));
        }
        return newInputs;
    }

    /**
     * Transform a list of eventually composite / nested features into a flat list of non composite / non nested features.
     *
     * @param originalFeatures a list of features
     * @return a flat list of features
     */
    public static List<Feature> getLinearizedFeatures(List<Feature> originalFeatures) {
        List<Feature> flattenedFeatures = new ArrayList<>();
        for (Feature f : originalFeatures) {
            linearizeFeature(flattenedFeatures, f);
        }
        return flattenedFeatures;
    }

    private static void linearizeFeature(List<Feature> flattenedFeatures, Feature f) {
        if (Type.UNDEFINED.equals(f.getType())) {
            if (f.getValue().getUnderlyingObject() instanceof Feature) {
                linearizeFeature(flattenedFeatures, (Feature) f.getValue().getUnderlyingObject());
            } else {
                flattenedFeatures.add(f);
            }
        } else if (Type.COMPOSITE.equals(f.getType())) {
            if (f.getValue().getUnderlyingObject() instanceof List) {
                List<Feature> features = (List<Feature>) f.getValue().getUnderlyingObject();
                for (Feature feature : features) {
                    linearizeFeature(flattenedFeatures, feature);
                }
            } else {
                flattenedFeatures.add(f);
            }
        } else {
            flattenedFeatures.add(f);
        }
    }

    /**
     * Build Predictions from PredictionInputs and PredictionOutputs.
     *
     * @param inputs prediction inputs
     * @param os prediction outputs
     * @return a list of predictions
     */
    public static List<Prediction> getPredictions(List<PredictionInput> inputs, List<PredictionOutput> os) {
        return IntStream.range(0, os.size())
                .mapToObj(i -> new SimplePrediction(inputs.get(i), os.get(i))).collect(Collectors.toList());
    }

    /**
     * Sample (with replacement) from a list of values.
     *
     * @param values the list to sample from
     * @param sampleSize the no. of samples to draw
     * @param random a random instance
     * @param <T> the type of values to sample
     * @return a list of sampled values
     */
    public static <T> List<T> sampleWithReplacement(List<T> values, int sampleSize, Random random) {
        if (sampleSize <= 0 || values.isEmpty()) {
            return Collections.emptyList();
        } else {
            return random
                    .ints(sampleSize, 0, values.size())
                    .mapToObj(values::get)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Replace an existing feature in a list with another feature.
     * The feature to be replaced is the one whose name is equals to the name of the feature to use as replacement.
     *
     * @param featureToUse feature to use as replacmement
     * @param existingFeatures list of features containing the feature to be replaced
     * @return a new list of features having the "replaced" feature
     */
    public static List<Feature> replaceFeatures(Feature featureToUse, List<Feature> existingFeatures) {
        List<Feature> newFeatures = new ArrayList<>();
        for (Feature f : existingFeatures) {
            Feature newFeature;
            if (f.getName().equals(featureToUse.getName())) {
                newFeature = FeatureFactory.copyOf(f, featureToUse.getValue());
            } else {
                if (Type.COMPOSITE == f.getType()) {
                    List<Feature> elements = (List<Feature>) f.getValue().getUnderlyingObject();
                    newFeature = FeatureFactory.newCompositeFeature(f.getName(), replaceFeatures(featureToUse, elements));
                } else {
                    newFeature = FeatureFactory.copyOf(f, f.getValue());
                }
            }
            newFeatures.add(newFeature);
        }
        return newFeatures;
    }

    /**
     * Persist a {@link PartialDependenceGraph} into a CSV file.
     *
     * @param partialDependenceGraph the PDP to persist
     * @param path the path to the CSV file to be created
     * @throws IOException whether any IO error occurs while writing the CSV
     */
    public static void toCSV(PartialDependenceGraph partialDependenceGraph, Path path) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path)) {
            List<Value> xAxis = partialDependenceGraph.getX();
            List<Value> yAxis = partialDependenceGraph.getY();
            CSVFormat format = CSVFormat.DEFAULT.withHeader(
                    partialDependenceGraph.getFeature().getName(), partialDependenceGraph.getOutput().getName());
            CSVPrinter printer = new CSVPrinter(writer, format);
            for (int i = 0; i < xAxis.size(); i++) {
                printer.printRecord(xAxis.get(i).asString(), yAxis.get(i).asString());
            }
        }
    }

    /**
     * Read a CSV file into a {@link DataDistribution} object.
     *
     * @param file the path to the CSV file
     * @param schema an ordered list of {@link Type}s as the 'schema', used to determine
     *        the {@link Type} of each feature / column
     * @return the parsed CSV as a {@link DataDistribution}
     * @throws IOException when failing at reading the CSV file
     * @throws MalformedInputException if any record in CSV has different size with respect to the specified schema
     */
    public static DataDistribution readCSV(Path file, List<Type> schema) throws IOException {
        List<PredictionInput> inputs = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            Iterable<CSVRecord> records = CSVFormat.RFC4180.withFirstRecordAsHeader().parse(reader);
            for (CSVRecord record : records) {
                int size = record.size();
                if (schema.size() == size) {
                    List<Feature> features = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        String s = record.get(i);
                        Type type = schema.get(i);
                        features.add(new Feature(record.getParser().getHeaderNames().get(i), type, new Value(s)));
                    }
                    inputs.add(new PredictionInput(features));
                } else {
                    throw new MalformedInputException(size);
                }
            }
        }
        return new PredictionInputsDataDistribution(inputs);
    }

    /**
     * Generate feature distributions from an existing (evantually small) {@link DataDistribution} for each {@link Feature}.
     * Each feature intervals (min, max) and density information (mean, stdDev) are generated using bootstrap, then
     * data points are sampled from a normal distribution (see {@link #generateData(double, double, int, Random)}).
     *
     * @param dataDistribution data distribution to take feature values from
     * @param perturbationContext perturbation context
     * @param featureDistributionSize desired size of generated feature distributions
     * @param draws number of times sampling from feature values is performed
     * @param sampleSize size of each sample draw
     * @param numericFeatureZonesMap high feature score zones
     * @return a map feature name -> generated feature distribution
     */
    public static Map<String, FeatureDistribution> boostrapFeatureDistributions(DataDistribution dataDistribution,
            PerturbationContext perturbationContext,
            int featureDistributionSize, int draws,
            int sampleSize, Map<String, HighScoreNumericFeatureZones> numericFeatureZonesMap) {
        Map<String, FeatureDistribution> featureDistributions = new HashMap<>();
        for (FeatureDistribution featureDistribution : dataDistribution.asFeatureDistributions()) {
            Feature feature = featureDistribution.getFeature();
            if (Type.NUMBER.equals(feature.getType())) {
                List<Value> values = featureDistribution.getAllSamples();
                double[] means = new double[draws];
                double[] stdDevs = new double[draws];
                double[] mins = new double[draws];
                double[] maxs = new double[draws];
                for (int i = 0; i < draws; i++) {
                    List<Value> sampledValues =
                            DataUtils.sampleWithReplacement(values, sampleSize, perturbationContext.getRandom());
                    double[] data = sampledValues.stream().mapToDouble(Value::asNumber).toArray();

                    double mean = DataUtils.getMean(data);
                    double stdDev = Math
                            .pow(DataUtils.getStdDev(data, mean), 2);
                    double min = Arrays.stream(data).min().orElse(Double.MIN_VALUE);
                    double max = Arrays.stream(data).max().orElse(Double.MAX_VALUE);
                    means[i] = mean;
                    stdDevs[i] = stdDev;
                    mins[i] = min;
                    maxs[i] = max;
                }
                double finalMean = DataUtils.getMean(means);
                double finalStdDev = Math.sqrt(DataUtils.getMean(stdDevs));
                double finalMin = DataUtils.getMean(mins);
                double finalMax = DataUtils.getMean(maxs);
                double[] doubles = DataUtils.generateData(finalMean, finalStdDev, featureDistributionSize,
                        perturbationContext.getRandom());
                double[] boundedData = Arrays.stream(doubles).map(d -> Math.min(Math.max(d, finalMin), finalMax)).toArray();
                HighScoreNumericFeatureZones highScoreNumericFeatureZones = numericFeatureZonesMap.get(feature.getName());
                double[] finaldata;
                if (highScoreNumericFeatureZones != null) {
                    double[] filteredData = DoubleStream.of(boundedData).filter(highScoreNumericFeatureZones::test).toArray();
                    // only use the filtered data if it's not discarding more than 50% of the points
                    if (filteredData.length > featureDistributionSize / 2) {
                        finaldata = filteredData;
                    } else {
                        finaldata = boundedData;
                    }
                } else {
                    finaldata = boundedData;
                }
                NumericFeatureDistribution numericFeatureDistribution = new NumericFeatureDistribution(feature, finaldata);
                featureDistributions.put(feature.getName(), numericFeatureDistribution);
            }
        }
        return featureDistributions;
    }

    public static List<Prediction> getScoreSortedPredictions(String outputName, PredictionProvider predictionProvider,
            DataDistribution dataDistribution)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<PredictionInput> inputs = dataDistribution.getAllSamples();
        List<PredictionOutput> predictionOutputs = predictionProvider.predictAsync(inputs)
                .get(Config.DEFAULT_ASYNC_TIMEOUT, Config.DEFAULT_ASYNC_TIMEUNIT);
        List<Prediction> predictions = DataUtils.getPredictions(inputs, predictionOutputs);

        return sortPredictionsByScore(outputName, predictions);
    }

    /**
     * Sort the predictions by Output#getScore, in descending order.
     *
     * @param outputName name of the output score used for sorting
     * @param predictions list of predictions to sort
     * @return the score-sorted list of predictions
     */
    public static List<Prediction> sortPredictionsByScore(String outputName, List<Prediction> predictions) {
        return predictions.stream().sorted((p1, p2) -> {
            Optional<Output> optionalOutput1 = p1.getOutput().getByName(outputName);
            Optional<Output> optionalOutput2 = p2.getOutput().getByName(outputName);
            if (optionalOutput1.isPresent() && optionalOutput2.isPresent()) {
                Output o1 = optionalOutput1.get();
                Output o2 = optionalOutput2.get();
                return Double.compare(o2.getScore(), o1.getScore());
            } else {
                return 0;
            }
        }).collect(Collectors.toList());
    }

    public static List<Prediction> getScoreSortedPredictions(PredictionProvider predictionProvider,
            DataDistribution dataDistribution)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<PredictionInput> inputs = dataDistribution.getAllSamples();
        List<PredictionOutput> predictionOutputs = predictionProvider.predictAsync(inputs)
                .get(Config.DEFAULT_ASYNC_TIMEOUT, Config.DEFAULT_ASYNC_TIMEUNIT);
        List<Prediction> predictions = DataUtils.getPredictions(inputs, predictionOutputs);

        // sort the predictions by Output#getScore, in descending order
        return predictions.stream().sorted((p1, p2) -> {
            List<Output> o1 = p1.getOutput().getOutputs();
            List<Output> o2 = p2.getOutput().getOutputs();
            return Double.compare(o2.stream().mapToDouble(Output::getScore).sum(), o1.stream().mapToDouble(Output::getScore).sum());
        }).collect(Collectors.toList());
    }

    /**
     * Generates a string out of the input features.
     *
     * @param input the prediction input
     * @return the string representation of the input features
     */
    public static String textify(PredictionInput input) {
        StringBuilder text = new StringBuilder();
        for (Feature f : getLinearizedFeatures(input.getFeatures())) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(f.getValue().asString());
        }
        return text.toString();
    }

    public static double gowerDistance(List<Feature> x, List<Feature> y, double lambda) {
        double[] xNum = x.stream().filter(f -> Type.NUMBER.equals(f.getType())).mapToDouble(f -> f.getValue().asNumber()).toArray();
        double[] yNum = y.stream().filter(f -> Type.NUMBER.equals(f.getType())).mapToDouble(f -> f.getValue().asNumber()).toArray();

        String xStr = x.stream().filter(f -> Type.TEXT.equals(f.getType())).map(f -> f.getValue().asString()).collect(Collectors.joining(" "));
        String yStr = y.stream().filter(f -> Type.TEXT.equals(f.getType())).map(f -> f.getValue().asString()).collect(Collectors.joining(" "));
        return euclideanDistance(xNum, yNum) + lambda * hammingDistance(xStr, yStr);
    }

}
