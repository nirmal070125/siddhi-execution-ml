package org.wso2.extension.siddhi.execution.ml.clustering.kmeans;

import org.wso2.extension.siddhi.execution.ml.clustering.kmeans.util.Clusterer;
import org.wso2.extension.siddhi.execution.ml.clustering.kmeans.util.DataPoint;
import org.wso2.extension.siddhi.execution.ml.clustering.kmeans.util.KMeansModel;
import org.wso2.extension.siddhi.execution.ml.clustering.kmeans.util.KMeansModelHolder;
import org.wso2.extension.siddhi.execution.ml.clustering.kmeans.util.Trainer;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.ReturnAttribute;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.event.stream.populater.ComplexEventPopulater;
import org.wso2.siddhi.core.executor.ConstantExpressionExecutor;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.query.processor.Processor;
import org.wso2.siddhi.core.query.processor.stream.StreamProcessor;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.exception.SiddhiAppValidationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;


@Extension(
        name = "kmeansincremental",
        namespace = "streamingml",
        description = "Performs K-Means clustering on a streaming data set. Data points can be of any dimension and the dimensionality should be passed as a parameter. " +
                "All data points to be processed by an instance of class Clusterer should be of the same dimensionality. The Euclidean distance is taken as the distance metric. " +
                "The algorithm resembles mini-batch K-Means. (refer Web-Scale K-Means Clustering by D.Sculley, Google, Inc.). Supports a given size window implementation. " +
                "For example: #window.length(10)#kmeans:cluster(dimensionality, k, maxIterations, train, x1, x2, .... , xd)" +
                "Model is trained for every specified number of events or when true is passed as train parameter. The training process initializes the first k distinct events in the window as" +
                "initial centroids. The dataPoints are assigned to the respective closest centroid.",
        parameters = {
                @Parameter(
                        name = "dimensionality",
                        description = "The number of dimensions need to represent dataPoint. Needs to be constant for all events in a single stream.",
                        type = {DataType.INT}
                ),
                @Parameter(
                        name = "k",
                        description = "The assumed number of natural clusters in the data set.",
                        type = {DataType.INT}
                ),
                @Parameter(
                        name = "max.iterations",
                        description = "Number of iterations, the process iterates until the number of maximum iterations is reached or the centroids do not change",
                        type = {DataType.INT}
                ),
                @Parameter(
                        name = "number.of.events.to.retrain",
                        description = "New cluster centers are found for given number of events",
                        optional = true,
                        type = DataType.INT,
                        defaultValue = "5"
                ),
                @Parameter(
                        name = "train",
                        optional = true,
                        description = "train the model for available amount of data",
                        type = DataType.BOOL,
                        defaultValue = "false"
                ),
                @Parameter(
                        name = "decay.rate",
                        description = "this is the decay rate of old data compared to new data. " +
                                "Value of this will be in [0,1]. 0 means only old data used and" +
                                "1 will mean that only new data is used",
                        type = {DataType.FLOAT}
                ),
                @Parameter(
                        name = "coordinate.values",
                        description = "This is a variable length argument. Depending on the dimensionality of data points we will receive coordinates along each axis.",
                        type = {DataType.DOUBLE}
                )

        },
        returnAttributes = {
                @ReturnAttribute(
                        name = "euclideanDistanceToClosestCentroid",
                        description = "Represents the Euclidean distance between the current data point and the closest centroid.",
                        type = {DataType.DOUBLE}
                ),
                @ReturnAttribute(
                        name = "closestCentroidCoordinatei",
                        description = "This is a variable length attribute. Depending on the dimensionality(d) we will return closestCentroidCoordinate1 to closestCentroidCoordinated",
                        type = {DataType.DOUBLE}
                )
        },
        examples = {
                @Example(
                        syntax = "from InputStream#kmeans:cluster(dimensionality, k, maxIterations, numberOfEventsToRetrain, decayRate, coordinateValue1, coordinateValue2)\"\n" +
                                "select coordinateValue1, coordinateValue2, euclideanDistanceToClosestCentroid, closestCentroidCoordinate1, closestCentroidCoordinate2\"\n" +
                                "insert into OutputStream",
                        description = "dimensionality =2, k=2, numberOfEventsToRetrain = 5, maxIterations=10. This will cluster the collected data points within the window for every 5 events" +
                                "and give output after the first 5 events"
                ),
        }
)
public class KMeansIncrementalSPExtension extends StreamProcessor {
    private int k;
    private int maxIterations=2;
    private float decayRate;
    private String modelName;


    private int numberOfEventsToRetrain=1;
    private int numberOfEventsReceived;
    private int coordinateStartIndex;
    private ArrayList<DataPoint> dataPointsArray = new ArrayList<>();

    private boolean modelTrained = false;
    private boolean initialTrained = false;
    private boolean decayRateGiven = false;
    private Clusterer clusterer;
    private int dimensionality;
    private double[] coordinateValues;
    private int minBatchSizeToTriggerSeparateThread = 10;
    private ExecutorService executorService;
    private final static Logger logger = Logger.getLogger(KMeansStreamProcessorExtension.class.getName());

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor processor,
                           StreamEventCloner streamEventCloner, ComplexEventPopulater complexEventPopulater) {

        while (streamEventChunk.hasNext()) {
            StreamEvent streamEvent = streamEventChunk.next();

            if (streamEvent.getType() == ComplexEvent.Type.CURRENT) {
                numberOfEventsReceived++;
                coordinateValues = new double[dimensionality];


                //validating and getting coordinate values
                for (int i=coordinateStartIndex; i<coordinateStartIndex+dimensionality; i++) {
                    Object content = attributeExpressionExecutors[i].execute(streamEvent);
                    if (content instanceof Double) {
                        coordinateValues[i-coordinateStartIndex] = (Double) content;
                    } else {
                        throw new SiddhiAppValidationException("Coordinate values of data point should be " +
                                "of type double but found " + attributeExpressionExecutors[i].getReturnType());
                    }
                }

                //creating a dataPoint with the received coordinate values
                DataPoint currentDataPoint = new DataPoint(dimensionality);
                currentDataPoint.setCoordinates(coordinateValues);
                dataPointsArray.add(currentDataPoint);

                //handling the training
                if ((!initialTrained) && (numberOfEventsToRetrain > 0) && (numberOfEventsReceived % numberOfEventsToRetrain == 0)) {
                    clusterer.cluster(dataPointsArray);
                    dataPointsArray.clear();
                    initialTrained = true;
                    modelTrained = true;
                } else if (numberOfEventsToRetrain > 0 && (numberOfEventsReceived % numberOfEventsToRetrain == 0)){
                    periodicTraining();

                }

                if (modelTrained) {
                    Object[] outputData;
                    outputData = clusterer.getAssociatedCentroidInfo(currentDataPoint);
                    complexEventPopulater.populateComplexEvent(streamEvent, outputData);
                } else {
                    streamEventChunk.remove();
                }
            } // should we need to handle RESET and EXPIRED?
        }
        nextProcessor.process(streamEventChunk);
    }

    private void periodicTraining() {
        logger.config("Traditional training");
        clusterer.updateCluster(dataPointsArray, decayRate);
        dataPointsArray.clear();
        modelTrained = true;
    }



    @Override
    protected List<Attribute> init(AbstractDefinition abstractDefinition, ExpressionExecutor[] attributeExpressionExecutors, ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        logger.setLevel(Level.ALL);
        //expressionExecutors[0] --> modelName
        if (!(attributeExpressionExecutors[0] instanceof ConstantExpressionExecutor)) {
            throw new SiddhiAppValidationException("modelName has to be a constant.");
        }
        Object zerothContent = attributeExpressionExecutors[0].execute(null);
        if (zerothContent instanceof String) {
            modelName = (String) zerothContent;
        } else {
            throw new SiddhiAppValidationException("modelName should be of type String but found " +
                    attributeExpressionExecutors[0].getReturnType());
        }

        //expressionExecutors[1] --> k or decayRate
        if (attributeExpressionExecutors[1].getReturnType() ==  Attribute.Type.INT) {
            if (attributeExpressionExecutors[1] instanceof ConstantExpressionExecutor) {
                Object firstContent = attributeExpressionExecutors[1].execute(null);
                logger.info("decayRate not specified. using default");
                decayRate=0.01f;
                coordinateStartIndex = 2;
                k = (Integer) firstContent;
            } else {
                throw new SiddhiAppValidationException("k has to be a constant.");
            }
        } else if (attributeExpressionExecutors[1].getReturnType() ==  Attribute.Type.FLOAT) {
            if (attributeExpressionExecutors[1] instanceof ConstantExpressionExecutor) {
                logger.info("decayRate is specified.");
                Object firstContent = attributeExpressionExecutors[1].execute(null);
                decayRate = (Float) firstContent;

                //expressionExecutors[2] --> k
                if (!(attributeExpressionExecutors[2] instanceof ConstantExpressionExecutor)) {
                    throw new SiddhiAppValidationException("k has to be a constant.");
                }
                Object secondContent = attributeExpressionExecutors[2].execute(null);
                if (secondContent instanceof Integer) {
                    coordinateStartIndex = 3;
                    k = (Integer) secondContent;
                } else {
                    throw new SiddhiAppValidationException("k should be of type int but found " +
                            attributeExpressionExecutors[2].getReturnType());
                }
            } else {
                throw new SiddhiAppValidationException("decayRate has to be a constant.");
            }
        } else {
            throw new SiddhiAppValidationException("k/decayRate should be of type int/float but found " +
                    attributeExpressionExecutors[1].getReturnType());
        }

        dimensionality = attributeExpressionExecutors.length - coordinateStartIndex;



        /*//new start

        numberOfEventsToRetrain = 1; //incremental mode by default
        decayRate = 0.001f; //default value
        coordinateStartIndex = 3;

        if (attributeExpressionExecutors[3].getReturnType() == Attribute.Type.INT){
            //logger.("HIIIIIIIIIIIII");
            if (attributeExpressionExecutors[3] instanceof ConstantExpressionExecutor) {
                Object thirdContent = attributeExpressionExecutors[3].execute(null);
                numberOfEventsToRetrain = (Integer) thirdContent;
                logger.info("numberOfEventsToRetrain is specified");
                if (attributeExpressionExecutors[4].getReturnType() == Attribute.Type.FLOAT) {
                    if (attributeExpressionExecutors[4] instanceof ConstantExpressionExecutor) {
                        Object fourthContent = attributeExpressionExecutors[4].execute(null);
                        decayRate = (Float) fourthContent;
                        logger.info("decay rate is specified");
                        coordinateStartIndex = 5;
                    } else {
                        throw new SiddhiAppValidationException("Decay rate has to be a constant");
                    }
                } else {
                    logger.info("decay rate not specified. using default");
                    coordinateStartIndex = 4;
                }
            } else {
                throw new SiddhiAppValidationException("numberOfEventsToRetrain has to be a constant");
            }

        } else if (attributeExpressionExecutors[3].getReturnType() == Attribute.Type.FLOAT) {
            if (attributeExpressionExecutors[3] instanceof ConstantExpressionExecutor) {
                logger.info("numberOfEventsToRetrain not specified. using default");
                Object thirdContent = attributeExpressionExecutors[3].execute(null);
                decayRate = (Float) thirdContent;
                logger.info("decay rate is specified");
                coordinateStartIndex = 4;
            } else {
                throw new SiddhiAppValidationException("decayRate has to be a constant");
            }
        } else if (attributeExpressionExecutors[3].getReturnType() == Attribute.Type.DOUBLE){
            logger.info("numberOfEventsToRetrain and decayRate both not specified. using default");
        } else {
            throw new SiddhiAppValidationException("Since fourth query parameter is constant it should be either" +
                    "numberOfEventsToRetrain or decayRate or the first coordinate of data point i.e int or float or double but found " +attributeExpressionExecutors[3].getReturnType());
        }
        *//*else {
            logger.info("numberOfEventsToRetrain and decay rate not specified.using default");
            coordinateStartIndex = 3;
        }*//*

        dimensionality = attributeExpressionExecutors.length - coordinateStartIndex;*/


        //new end

        /*//expressionExecutors[3] --> numberOfEventsToRetrain
        if (!(attributeExpressionExecutors[3] instanceof ConstantExpressionExecutor)) {
            throw new SiddhiAppValidationException("numberOfEventsToRetrain has to be a constant.");
        }
        Object thirdContent = attributeExpressionExecutors[3].execute(null);
        if (thirdContent instanceof Integer) {
            numberOfEventsToRetrain = (Integer) thirdContent;
        } else {
            throw new SiddhiAppValidationException("Number of events to trigger retraining" +
                    "should be of type int but found " +attributeExpressionExecutors[3].getReturnType());
        }

        //expressionExecutors[4] --> decayRate or first dim of datapoint
        if (attributeExpressionExecutors[4].getReturnType() == Attribute.Type.FLOAT) {
            Object fourthContent = attributeExpressionExecutors[4].execute(null);
            if (fourthContent instanceof Float) {
                logger.config("decay rate specified");
                decayRateGiven = true;
                decayRate = (Float) fourthContent;
                dimensionality = attributeExpressionExecutors.length - 5;
            }
        } else if (attributeExpressionExecutors[4].getReturnType() == Attribute.Type.DOUBLE) {
            logger.config("decay rate not specified");
            decayRateGiven = false;
            decayRate = 0.001f; // default value for online approach. should tune
            dimensionality = attributeExpressionExecutors.length - 4;
        }*/
        String siddhiAppName = siddhiAppContext.getName();
        modelName = modelName+"."+siddhiAppName;
        logger.info(modelName);
        clusterer = new Clusterer(k,maxIterations, modelName);

        executorService = siddhiAppContext.getExecutorService();
        logger.setLevel(Level.ALL);

        List<Attribute> attributeList = new ArrayList<>(1+dimensionality);
        attributeList.add(new Attribute("euclideanDistanceToClosestCentroid", Attribute.Type.DOUBLE));
        for (int i=1; i<=dimensionality; i++) {
            attributeList.add(new Attribute("closestCentroidCoordinate"+i, Attribute.Type.DOUBLE));
        }
        return attributeList;
    }


    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }

    @Override
    public Map<String, Object> currentState() {
        Map<String, Object> map = new HashMap();
        map.put("data", dataPointsArray);
        map.put("modelTrained", modelTrained);
        map.put("numberOfEventsReceived", numberOfEventsReceived);
        map.put("model", KMeansModelHolder.getInstance().getClonedKMeansModelMap());
        return map;
    }

    @Override
    public void restoreState(Map<String, Object> map) {
        dataPointsArray = (ArrayList<DataPoint>) map.get("data");
        modelTrained = (Boolean) map.get("modelTrained");
        numberOfEventsReceived = (Integer) map.get("numberOfEventsReceived");
        KMeansModel model1 = (KMeansModel) map.get("model");
        clusterer.setModel(model1);
    }


}

