/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.execution.ml.classification.perceptron.util;

import org.wso2.extension.siddhi.execution.ml.util.MathUtil;

import java.io.Serializable;

/**
 * Represents a linear Perceptron Model
 */
public class PerceptronModel implements Serializable {
    private double[] weights;
    private double bias = 0.0;
    private double threshold = 0.5;
    private double learningRate = 0.1;

    public PerceptronModel() {
    }

    public PerceptronModel(PerceptronModel model) {
        this.weights = model.weights;
        this.bias = model.bias;
        this.threshold = model.threshold;
        this.learningRate = model.learningRate;
    }

    public double[] update(Boolean label, double[] features) {
        Boolean predictedLabel = this.classify(features);

        if (!label.equals(predictedLabel)) {
            Double error = Boolean.TRUE.equals(label) ? 1.0 : -1.0;

            // Get correction
            Double correction;
            for (int i = 0; i < features.length; i++) {
                correction = features[i] * error * this.learningRate;
                this.weights[i] = this.weights[i] + correction;
            }
        }
        return weights;
    }

    public Boolean classify(double[] features) {
        if (this.weights == null) {
            this.initWeights(features.length);
        }

        Double evaluation = MathUtil.dot(features, weights) + this.bias;

        Boolean prediction = evaluation > this.threshold ? Boolean.TRUE : Boolean.FALSE;
        return prediction;
    }

    private void initWeights(int size) {
        this.weights = new double[size];
    }

    public double[] getWeights() {
        return weights;
    }

    public void setWeights(double[] weights) {
        this.weights = weights;
    }

    public double getBias() {
        return bias;
    }

    public void setBias(double bias) {
        this.bias = bias;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public double getLearningRate() {
        return learningRate;
    }

    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }
}
