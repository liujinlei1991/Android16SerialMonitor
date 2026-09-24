package com.jinle.serialmonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Collects four views and converts the 16 radar layers into ellipse measurements. */
final class MeasurementSession {
    static final int PHASE_FRONT = 0;
    static final int PHASE_LEFT = 1;
    static final int PHASE_BACK = 2;
    static final int PHASE_RIGHT = 3;
    static final int PHASE_COUNT = 4;
    static final int LAYER_COUNT = 16;
    static final double SENSOR_CENTER_DISTANCE_MM = 500.0;
    static final double FIRST_SENSOR_HEIGHT_MM = 635.0;
    static final double SENSOR_SPACING_MM = 40.0;

    static final String[] PHASE_NAMES = {"正面", "左侧面", "背面", "右侧面"};

    @SuppressWarnings("unchecked")
    private final List<Double>[][] samples = new ArrayList[PHASE_COUNT][LAYER_COUNT];

    MeasurementSession() {
        for (int phase = 0; phase < PHASE_COUNT; phase++) {
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                samples[phase][layer] = new ArrayList<>();
            }
        }
    }

    synchronized void addSample(int phase, int layer, double distanceMm) {
        if (phase < 0 || phase >= PHASE_COUNT || layer < 0 || layer >= LAYER_COUNT) return;
        // Broad physical gate. Temporal median and cross-height smoothing reject remaining spikes.
        if (!Double.isFinite(distanceMm) || distanceMm < 100.0 || distanceMm > 600.0) return;
        samples[phase][layer].add(distanceMm);
    }

    synchronized int getSampleCount(int phase, int layer) {
        return samples[phase][layer].size();
    }

    synchronized int getPhaseSampleCount(int phase) {
        int count = 0;
        for (int layer = 0; layer < LAYER_COUNT; layer++) count += samples[phase][layer].size();
        return count;
    }

    synchronized int getPhaseValidLayerCount(int phase) {
        int count = 0;
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            if (samples[phase][layer].size() >= 3) count++;
        }
        return count;
    }

    synchronized void clearPhase(int phase) {
        if (phase < 0 || phase >= PHASE_COUNT) return;
        for (int layer = 0; layer < LAYER_COUNT; layer++) samples[phase][layer].clear();
    }

    synchronized Result calculate() {
        double[][] distances = new double[PHASE_COUNT][LAYER_COUNT];
        int[][] counts = new int[PHASE_COUNT][LAYER_COUNT];
        for (int phase = 0; phase < PHASE_COUNT; phase++) {
            for (int layer = 0; layer < LAYER_COUNT; layer++) {
                counts[phase][layer] = samples[phase][layer].size();
                distances[phase][layer] = samples[phase][layer].size() >= 3
                        ? median(samples[phase][layer]) : Double.NaN;
            }
        }

        double[] height = new double[LAYER_COUNT];
        double[] width = new double[LAYER_COUNT];
        double[] depth = new double[LAYER_COUNT];
        double[] circumference = new double[LAYER_COUNT];
        Arrays.fill(width, Double.NaN);
        Arrays.fill(depth, Double.NaN);
        Arrays.fill(circumference, Double.NaN);

        int validLayers = 0;
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            height[layer] = FIRST_SENSOR_HEIGHT_MM + SENSOR_SPACING_MM * layer;
            double frontRadius = SENSOR_CENTER_DISTANCE_MM - distances[PHASE_FRONT][layer];
            double leftRadius = SENSOR_CENTER_DISTANCE_MM - distances[PHASE_LEFT][layer];
            double backRadius = SENSOR_CENTER_DISTANCE_MM - distances[PHASE_BACK][layer];
            double rightRadius = SENSOR_CENTER_DISTANCE_MM - distances[PHASE_RIGHT][layer];
            if (!validRadius(frontRadius) || !validRadius(leftRadius) ||
                    !validRadius(backRadius) || !validRadius(rightRadius)) continue;

            depth[layer] = frontRadius + backRadius;
            width[layer] = leftRadius + rightRadius;
            if (depth[layer] < 120.0 || depth[layer] > 650.0 ||
                    width[layer] < 120.0 || width[layer] > 650.0) {
                depth[layer] = Double.NaN;
                width[layer] = Double.NaN;
                continue;
            }
            circumference[layer] = ellipseCircumference(width[layer] / 2.0, depth[layer] / 2.0);
            validLayers++;
        }

        double[] smoothWidth = smoothMedian3(width);
        double[] smoothDepth = smoothMedian3(depth);
        double[] smoothCircumference = new double[LAYER_COUNT];
        Arrays.fill(smoothCircumference, Double.NaN);
        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            if (Double.isFinite(smoothWidth[layer]) && Double.isFinite(smoothDepth[layer])) {
                smoothCircumference[layer] = ellipseCircumference(
                        smoothWidth[layer] / 2.0, smoothDepth[layer] / 2.0);
            }
        }

        // Adult anatomical search bands for the fixed 635..1235 mm installation.
        int waist = findMinimum(smoothCircumference, 5, 12); // 835..1115 mm
        int hipUpper = waist > 1 ? Math.min(10, waist - 1) : 10;
        int hip = findMaximum(smoothCircumference, 1, hipUpper); // below the waist
        if (waist < 0) waist = findMinimum(smoothCircumference, 0, LAYER_COUNT - 1);
        if (hip < 0 && waist > 0) hip = findMaximum(smoothCircumference, 0, waist - 1);

        String warning = null;
        if (validLayers < 10 || waist < 0 || hip < 0) {
            warning = "有效层数不足，请保持站位并重新测量";
        } else if (smoothCircumference[hip] <= smoothCircumference[waist]) {
            warning = "臀围未明显大于腰围，请检查站位或衣物";
        }

        return new Result(height, smoothWidth, smoothDepth, smoothCircumference,
                distances, counts, validLayers, waist, hip, warning);
    }

    private static boolean validRadius(double radius) {
        return Double.isFinite(radius) && radius >= 40.0 && radius <= 350.0;
    }

    private static double median(List<Double> source) {
        List<Double> sorted = new ArrayList<>(source);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double[] smoothMedian3(double[] source) {
        double[] result = new double[source.length];
        Arrays.fill(result, Double.NaN);
        for (int i = 0; i < source.length; i++) {
            List<Double> neighborhood = new ArrayList<>(3);
            for (int j = Math.max(0, i - 1); j <= Math.min(source.length - 1, i + 1); j++) {
                if (Double.isFinite(source[j])) neighborhood.add(source[j]);
            }
            if (neighborhood.size() >= 2 || (neighborhood.size() == 1 && Double.isFinite(source[i]))) {
                result[i] = median(neighborhood);
            }
        }
        return result;
    }

    private static double ellipseCircumference(double a, double b) {
        double h = Math.pow(a - b, 2.0) / Math.pow(a + b, 2.0);
        return Math.PI * (a + b) * (1.0 + 3.0 * h / (10.0 + Math.sqrt(4.0 - 3.0 * h)));
    }

    private static int findMinimum(double[] values, int from, int to) {
        int selected = -1;
        for (int i = Math.max(0, from); i <= Math.min(values.length - 1, to); i++) {
            if (Double.isFinite(values[i]) && (selected < 0 || values[i] < values[selected])) selected = i;
        }
        return selected;
    }

    private static int findMaximum(double[] values, int from, int to) {
        int selected = -1;
        for (int i = Math.max(0, from); i <= Math.min(values.length - 1, to); i++) {
            if (Double.isFinite(values[i]) && (selected < 0 || values[i] > values[selected])) selected = i;
        }
        return selected;
    }

    static final class Result {
        final double[] heightsMm;
        final double[] widthsMm;
        final double[] depthsMm;
        final double[] circumferencesMm;
        final double[][] medianDistancesMm;
        final int[][] sampleCounts;
        final int validLayers;
        final int waistLayer;
        final int hipLayer;
        final String warning;

        Result(double[] heightsMm, double[] widthsMm, double[] depthsMm,
               double[] circumferencesMm, double[][] medianDistancesMm,
               int[][] sampleCounts, int validLayers, int waistLayer, int hipLayer,
               String warning) {
            this.heightsMm = heightsMm;
            this.widthsMm = widthsMm;
            this.depthsMm = depthsMm;
            this.circumferencesMm = circumferencesMm;
            this.medianDistancesMm = medianDistancesMm;
            this.sampleCounts = sampleCounts;
            this.validLayers = validLayers;
            this.waistLayer = waistLayer;
            this.hipLayer = hipLayer;
            this.warning = warning;
        }

        boolean hasMeasurement() {
            return waistLayer >= 0 && hipLayer >= 0 &&
                    Double.isFinite(circumferencesMm[waistLayer]) &&
                    Double.isFinite(circumferencesMm[hipLayer]);
        }

        double waistCm() {
            return hasMeasurement() ? circumferencesMm[waistLayer] / 10.0 : Double.NaN;
        }

        double hipCm() {
            return hasMeasurement() ? circumferencesMm[hipLayer] / 10.0 : Double.NaN;
        }

        double waistHipRatio() {
            return hasMeasurement() ? waistCm() / hipCm() : Double.NaN;
        }

        String summary() {
            if (!hasMeasurement()) return "数据不足，无法计算腰臀围";
            return String.format(Locale.CHINA, "腰围 %.1f cm · 臀围 %.1f cm · 腰臀比 %.2f",
                    waistCm(), hipCm(), waistHipRatio());
        }
    }
}
