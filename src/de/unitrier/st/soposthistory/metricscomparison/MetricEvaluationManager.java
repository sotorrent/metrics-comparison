package de.unitrier.st.soposthistory.metricscomparison;

import de.unitrier.st.soposthistory.gt.PostGroundTruth;
import de.unitrier.st.soposthistory.version.PostVersionList;
import org.apache.commons.csv.*;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

import static de.unitrier.st.soposthistory.util.Util.getClassLogger;

public class MetricEvaluationManager implements Runnable {
    private static int threadIdCounter = 0;

    private static Logger logger = null;
    static final CSVFormat csvFormatPostIds;
    static final CSVFormat csvFormatMetricEvaluationPerPost;
    public static final CSVFormat csvFormatMetricEvaluationPerVersion;
    private static final CSVFormat csvFormatMetricEvaluationPerSample;
    private static final Path DEFAULT_OUTPUT_DIR = Paths.get("output");

    private int threadId;
    private String sampleName;
    private boolean addDefaultMetricsAndThresholds;
    private boolean randomizeOrder;
    private boolean validate;
    private int numberOfRepetitions;
    private int threadCount;

    private Path postIdPath;
    private Path postHistoryPath;
    private Path groundTruthPath;
    private Path outputDirPath;

    private Set<Integer> postIds;
    private Map<Integer, PostGroundTruth> postGroundTruths; // postId -> PostGroundTruth
    private Map<Integer, PostVersionList> postVersionLists; // postId -> PostVersionList

    private List<SimilarityMetric> similarityMetrics;
    private List<MetricEvaluationPerSample> metricEvaluationsPerSample;

    private boolean initialized;

    static {
        // configure logger
        try {
            logger = getClassLogger(MetricEvaluationManager.class);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // configure CSV format for list of PostIds
        csvFormatPostIds = CSVFormat.DEFAULT
                .withHeader("PostId", "PostTypeId", "VersionCount")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\');

        // configure CSV format for metric comparison results (per post, i.e., per PostVersionList)
        csvFormatMetricEvaluationPerPost = CSVFormat.DEFAULT
                .withHeader("MetricType", "Metric", "Threshold", "PostId", "PostVersionCount", "PostBlockVersionCount", "PossibleConnections", "RuntimeText", "TextBlockVersionCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText", "FalsePositivesText", "FalseNegativesText", "FailedPredecessorComparisonsText", "RuntimeCode", "CodeBlockVersionCount", "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode", "FailedPredecessorComparisonsCode")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\')
                .withNullString("null");

        // configure CSV format for metric comparison results (per version, i.e., per PostHistoryId)
        csvFormatMetricEvaluationPerVersion = CSVFormat.DEFAULT
                .withHeader("MetricType", "Metric", "Threshold", "PostId", "PostHistoryId", "PossibleConnections", "RuntimeText", "TextBlockCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText", "FalsePositivesText", "FalseNegativesText", "FailedPredecessorComparisonsText", "RuntimeCode", "CodeBlockCount", "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode", "FailedPredecessorComparisonsCode")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\')
                .withNullString("null");

        // configure CSV format for aggregated metric comparison results (per (metric, threshold) combination)
        csvFormatMetricEvaluationPerSample = CSVFormat.DEFAULT
                .withHeader("MetricType", "Metric", "Threshold", "YoudensJText", "RuntimeText", "YoudensJCode", "RuntimeCode", "PostCount", "PostVersionCount", "PostBlockVersionCount", "PossibleConnections", "TextBlockVersionCount", "PossibleConnectionsText", "TruePositivesText", "TrueNegativesText", "FalsePositivesText", "FalseNegativesText", "FailuresText", "PrecisionText", "RecallText", "SensitivityText", "SpecificityText", "FailureRateText", "CodeBlockVersionCount", "PossibleConnectionsCode", "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode", "FailuresCode", "PrecisionCode", "RecallCode", "SensitivityCode", "SpecificityCode", "FailureRateCode")
                .withDelimiter(';')
                .withQuote('"')
                .withQuoteMode(QuoteMode.MINIMAL)
                .withEscape('\\')
                .withNullString("null");
    }

    private MetricEvaluationManager(String sampleName, Path postIdPath,
                                    Path postHistoryPath, Path groundTruthPath, Path outputDirPath,
                                    boolean validate, boolean addDefaultMetricsAndThresholds, boolean randomizeOrder,
                                    int numberOfRepetitions, int threadCount) {
        this.threadId = -1;
        this.sampleName = sampleName;

        this.postIdPath = postIdPath;
        this.postHistoryPath = postHistoryPath;
        this.groundTruthPath = groundTruthPath;
        this.outputDirPath = outputDirPath;

        this.validate = validate;
        this.addDefaultMetricsAndThresholds = addDefaultMetricsAndThresholds;
        this.randomizeOrder = randomizeOrder;
        this.numberOfRepetitions = numberOfRepetitions;
        this.threadCount = threadCount;

        this.postIds = new HashSet<>();
        this.postGroundTruths = new HashMap<>();
        this.postVersionLists = new HashMap<>();

        this.similarityMetrics = new LinkedList<>();
        this.metricEvaluationsPerSample = new LinkedList<>();

        this.initialized = false;
    }

    public static final MetricEvaluationManager DEFAULT = new MetricEvaluationManager(
            "SampleName",
            null,
            null,
            null,
            DEFAULT_OUTPUT_DIR,
            true,
            true,
            true,
            4,
            1
    );

    public MetricEvaluationManager withName(String name) {
        return new MetricEvaluationManager(name, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withInputPaths(Path postIdPath, Path postHistoryPath, Path groundTruthPath) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withOutputDirPath(Path outputDirPath) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withValidate(boolean validate) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withAddDefaultMetricsAndThresholds(boolean addDefaultMetricsAndThresholds) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withRandomizeOrder(boolean randomizeOrder) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withNumberOfRepetitions(int numberOfRepetitions) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager withThreadCount(int threadCount) {
        return new MetricEvaluationManager(sampleName, postIdPath, postHistoryPath, groundTruthPath, outputDirPath,
                validate, addDefaultMetricsAndThresholds, randomizeOrder, numberOfRepetitions, threadCount
        );
    }

    public MetricEvaluationManager initialize() {
        if (addDefaultMetricsAndThresholds) {
            addDefaultSimilarityMetricsAndThresholds();
        }

        // ensure that input file exists (directories are tested in read methods)
        if (!Files.exists(postIdPath) || Files.isDirectory(postIdPath)) {
            throw new IllegalArgumentException("File not found: " + postIdPath);
        }

        logger.info("Creating new MetricEvaluationManager for sample " + sampleName + " ...");

        try (CSVParser csvParser = new CSVParser(new FileReader(postIdPath.toFile()), csvFormatPostIds.withFirstRecordAsHeader())) {

            logger.info("Reading PostIds from CSV file " + postIdPath.toFile().toString() + " ...");

            for (CSVRecord currentRecord : csvParser) {
                int postId = Integer.parseInt(currentRecord.get("PostId"));
                int postTypeId = Integer.parseInt(currentRecord.get("PostTypeId"));
                int versionCount = Integer.parseInt(currentRecord.get("VersionCount"));

                // add post id to set
                postIds.add(postId);

                // read post version list
                PostVersionList newPostVersionList = PostVersionList.readFromCSV(
                        postHistoryPath, postId, postTypeId, false
                );
                newPostVersionList.normalizeLinks();

                if (newPostVersionList.size() != versionCount) {
                    throw new IllegalArgumentException("Version count expected to be " + versionCount
                            + ", but was " + newPostVersionList.size()
                    );
                }

                postVersionLists.put(postId, newPostVersionList);

                // read ground truth
                PostGroundTruth newPostGroundTruth = PostGroundTruth.readFromCSV(groundTruthPath, postId);

                if (newPostGroundTruth.getPossibleConnections() != newPostVersionList.getPossibleConnections()) {
                    throw new IllegalArgumentException("Number of possible connections in ground truth is different " +
                            "from number of possible connections in post history.");
                }

                postGroundTruths.put(postId, newPostGroundTruth);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (validate && ! validate()) {
            throw new IllegalArgumentException("Post ground truth files and post version history files do not match.");
        }

        initialized = true;

        return this;
    }

    public boolean validate() {
        for (MetricEvaluationPerSample sample : metricEvaluationsPerSample) {
            if (!sample.validate()) {
                return false;
            }
        }
        return true;
    }

    private void prepareEvaluation() {
        for (SimilarityMetric similarityMetric : similarityMetrics) {
            MetricEvaluationPerSample evaluationPerSample = new MetricEvaluationPerSample(
                    sampleName,
                    similarityMetric,
                    postIds,
                    postVersionLists,
                    postGroundTruths,
                    numberOfRepetitions,
                    randomizeOrder
            );
            evaluationPerSample.prepareEvaluation();
            metricEvaluationsPerSample.add(evaluationPerSample);
        }
    }

    private void randomizeOrder() {
        Collections.shuffle(metricEvaluationsPerSample, new Random());
    }

    @Override
    public void run() {
        threadId = ++threadIdCounter;
        logger.info("Thread " + threadId + " started for sample " + sampleName + "...");

        if (!initialized) {
            initialize();
        }

        prepareEvaluation();

        for (int currentRepetition = 1; currentRepetition <= numberOfRepetitions; currentRepetition++) {
            if (randomizeOrder) {
                logger.info( "Thread " + threadId + ": Randomizing order of similarity metrics for sample " + sampleName + "...");
                randomizeOrder();
            }

            int size = metricEvaluationsPerSample.size();
            for (int i = 0; i < size; i++) {
                MetricEvaluationPerSample evaluationPerSample = metricEvaluationsPerSample.get(i);

                // Locale.ROOT -> force '.' as decimal separator
                String progress = String.format(Locale.ROOT, "%.2f%%", (((double)(i+1))/size*100));
                logger.info( "Thread " + threadId + ": Starting evaluation " + (i+1) + " of " + size + " (" + progress + "), "
                        + "repetition " + currentRepetition + " of " + numberOfRepetitions + "...");

                synchronized (MetricEvaluationManager.class) {
                     evaluationPerSample.startEvaluation(currentRepetition);
                }
            }
        }

        logger.info("Saving results for sample " + sampleName + "...");
        writeToCSV();
        logger.info("Results saved.");
    }

    private void writeToCSV() {
        // create output directory if it does not exist
        try {
            Files.createDirectories(outputDirPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // output file by version
        File outputFilePerVersion = Paths.get(this.outputDirPath.toString(), sampleName + "_per_version.csv").toFile();
        if (outputFilePerVersion.exists()) {
            if (!outputFilePerVersion.delete()) {
                throw new IllegalStateException("Error while deleting output file: " + outputFilePerVersion);
            }
        }

        // output file aggregated by post
        File outputFilePerPost = Paths.get(this.outputDirPath.toString(), sampleName + "_per_post.csv").toFile();
        if (outputFilePerPost.exists()) {
            if (!outputFilePerPost.delete()) {
                throw new IllegalStateException("Error while deleting output file: " + outputFilePerPost);
            }
        }

        // output file aggregated by sample
        File outputFilePerSample = Paths.get(this.outputDirPath.toString(), sampleName + "_per_sample.csv").toFile();
        if (outputFilePerSample.exists()) {
            if (!outputFilePerSample.delete()) {
                throw new IllegalStateException("Error while deleting output file: " + outputFilePerSample);
            }
        }

        logger.info("Writing metric evaluation results per version to CSV file " + outputFilePerVersion.getName() + " ...");
        logger.info("Writing metric evaluation results per post to CSV file " + outputFilePerPost.getName() + " ...");
        logger.info("Writing metric evaluation results per sample to CSV file " + outputFilePerSample.getName() + " ...");
        try (CSVPrinter csvPrinterVersion = new CSVPrinter(new FileWriter(outputFilePerVersion), csvFormatMetricEvaluationPerVersion);
             CSVPrinter csvPrinterPost = new CSVPrinter(new FileWriter(outputFilePerPost), csvFormatMetricEvaluationPerPost);
             CSVPrinter csvPrinterSample = new CSVPrinter(new FileWriter(outputFilePerSample), csvFormatMetricEvaluationPerSample)) {

            // header is automatically written

            // write results per sample, per post, and per version
            for (MetricEvaluationPerSample evaluationPerSample : metricEvaluationsPerSample) {
                evaluationPerSample.writeToCSV(csvPrinterSample);
                for (MetricEvaluationPerPost evaluationPerPost : evaluationPerSample) {
                    evaluationPerPost.writeToCSV(csvPrinterPost, csvPrinterVersion);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<Integer, PostGroundTruth> getPostGroundTruths() {
        return postGroundTruths;
    }

    public Map<Integer, PostVersionList> getPostVersionLists() {
        return postVersionLists;
    }

    public void addSimilarityMetric(SimilarityMetric metric) {
        similarityMetrics.add(metric);
    }

    public MetricEvaluationPerPost getMetricEvaluation(int postId, String metricName, double threshold) {
        for (MetricEvaluationPerSample evaluationPerSample : metricEvaluationsPerSample) {
            if (evaluationPerSample.getSimilarityMetric().getName().equals(metricName)
                    && evaluationPerSample.getSimilarityMetric().getThreshold() == threshold) {
                for (MetricEvaluationPerPost evaluationPerPost : evaluationPerSample) {
                    if (evaluationPerPost.getPostId() == postId) {
                        return evaluationPerPost;
                    }
                }
            }
        }

        throw new IllegalStateException("Similarity metric " + metricName + " not found in evaluation samples.");
    }

    String getSampleName() {
        return sampleName;
    }

    static void aggregateAndWriteSampleResults(List<MetricEvaluationManager> managers, File outputFile) {
        // aggregate results over all samples
        Map<SimilarityMetric, MetricResult> aggregatedMetricResultsText = new HashMap<>();
        Map<SimilarityMetric, MetricResult> aggregatedMetricResultsCode = new HashMap<>();
        int maxFailuresText = 0;
        int maxFailuresCode = 0;

        for (int i=0; i<managers.size(); i++) {
            MetricEvaluationManager manager = managers.get(i);
            if (i==0) {
                for (MetricEvaluationPerSample evaluation : manager.metricEvaluationsPerSample) {
                    MetricResult resultText = evaluation.getResultAggregatedBySampleText();
                    maxFailuresText = resultText.getFailedPredecessorComparisons();
                    aggregatedMetricResultsText.put(evaluation.getSimilarityMetric(), resultText);

                    MetricResult resultCode = evaluation.getResultAggregatedBySampleCode();
                    maxFailuresCode = resultCode.getFailedPredecessorComparisons();
                    aggregatedMetricResultsCode.put(evaluation.getSimilarityMetric(), resultCode);
                }
            } else {
                for (MetricEvaluationPerSample evaluation : manager.metricEvaluationsPerSample) {
                    MetricResult newResultText = evaluation.getResultAggregatedBySampleText();
                    maxFailuresText = Math.max(maxFailuresText, newResultText.getFailedPredecessorComparisons());
                    MetricResult resultText = aggregatedMetricResultsText.get(newResultText.getSimilarityMetric());
                    resultText.add(newResultText);

                    MetricResult newResultCode = evaluation.getResultAggregatedBySampleCode();
                    maxFailuresCode = Math.max(maxFailuresCode, newResultCode.getFailedPredecessorComparisons());
                    MetricResult resultCode = aggregatedMetricResultsCode.get(newResultCode.getSimilarityMetric());
                    resultCode.add(newResultCode);
                }
            }
        }

        try (CSVPrinter csvPrinterAggregated = new CSVPrinter(new FileWriter(outputFile), csvFormatMetricEvaluationPerSample)) {
            for (SimilarityMetric similarityMetric : aggregatedMetricResultsText.keySet()) {
                MetricResult aggregatedResultText = aggregatedMetricResultsText.get(similarityMetric);
                MetricResult aggregatedResultCode = aggregatedMetricResultsCode.get(similarityMetric);

                // "MetricType", "Metric", "Threshold",
                // "YoudensJText", "RuntimeText", "YoudensJCode", "RuntimeCode",
                // "PostCount", "PostVersionCount", "PostBlockVersionCount", "PossibleConnections",
                // "TextBlockVersionCount", "PossibleConnectionsText",
                // "TruePositivesText", "TrueNegativesText", "FalsePositivesText", "FalseNegativesText", "FailuresText",
                // "PrecisionText", "RecallText", "SensitivityText", "SpecificityText", "FailureRateText",
                // "CodeBlockVersionCount", "PossibleConnectionsCode",
                // "TruePositivesCode", "TrueNegativesCode", "FalsePositivesCode", "FalseNegativesCode", "FailuresCode",
                // "PrecisionCode", "RecallCode", "SensitivityCode", "SpecificityCode", "FailureRateCode"
                csvPrinterAggregated.printRecord(
                        similarityMetric.getType(),
                        similarityMetric.getName(),
                        similarityMetric.getThreshold(),

                        aggregatedResultText.getYoudensJ(),
                        aggregatedResultText.getRuntime(),
                        aggregatedResultCode.getYoudensJ(),
                        aggregatedResultCode.getRuntime(),

                        aggregatedResultText.getPostCount(),
                        aggregatedResultText.getPostVersionCount(),
                        aggregatedResultText.getPostBlockVersionCount() + aggregatedResultCode.getPostBlockVersionCount(),
                        aggregatedResultText.getPossibleConnections() + aggregatedResultCode.getPossibleConnections(),

                        aggregatedResultText.getPostBlockVersionCount(),
                        aggregatedResultText.getPossibleConnections(),

                        aggregatedResultText.getTruePositives(),
                        aggregatedResultText.getTrueNegatives(),
                        aggregatedResultText.getFalsePositives(),
                        aggregatedResultText.getFalseNegatives(),
                        aggregatedResultText.getFailedPredecessorComparisons(),

                        aggregatedResultText.getPrecision(),
                        aggregatedResultText.getRecall(),
                        aggregatedResultText.getSensitivity(),
                        aggregatedResultText.getSpecificity(),
                        aggregatedResultText.getFailureRate(maxFailuresText),

                        aggregatedResultCode.getPostBlockVersionCount(),
                        aggregatedResultCode.getPossibleConnections(),

                        aggregatedResultCode.getTruePositives(),
                        aggregatedResultCode.getTrueNegatives(),
                        aggregatedResultCode.getFalsePositives(),
                        aggregatedResultCode.getFalseNegatives(),
                        aggregatedResultCode.getFailedPredecessorComparisons(),

                        aggregatedResultCode.getPrecision(),
                        aggregatedResultCode.getRecall(),
                        aggregatedResultCode.getSensitivity(),
                        aggregatedResultCode.getSpecificity(),
                        aggregatedResultCode.getFailureRate(maxFailuresCode)
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        logger.info("Aggregated results over all samples saved.");
    }

    private void addDefaultSimilarityMetricsAndThresholds() {
        List<Double> similarityThresholds = Arrays.asList(0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9); // TODO: add also 0.35, 0.45, 0.55, 0.65, 0.75, 0.85

        for (double similarityThreshold : similarityThresholds) {

            // ****** Edit based *****

            similarityMetrics.add(new SimilarityMetric(
                    "equals",
                    de.unitrier.st.stringsimilarity.edit.Variants::equals,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "equalsNormalized",
                    de.unitrier.st.stringsimilarity.edit.Variants::equalsNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "levenshtein",
                    de.unitrier.st.stringsimilarity.edit.Variants::levenshtein,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "levenshteinNormalized",
                    de.unitrier.st.stringsimilarity.edit.Variants::levenshteinNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );

            similarityMetrics.add(new SimilarityMetric(
                    "damerauLevenshtein",
                    de.unitrier.st.stringsimilarity.edit.Variants::damerauLevenshtein,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "damerauLevenshteinNormalized",
                    de.unitrier.st.stringsimilarity.edit.Variants::damerauLevenshteinNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );

            similarityMetrics.add(new SimilarityMetric(
                    "optimalAlignment",
                    de.unitrier.st.stringsimilarity.edit.Variants::optimalAlignment,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "optimalAlignmentNormalized",
                    de.unitrier.st.stringsimilarity.edit.Variants::optimalAlignmentNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );

            similarityMetrics.add(new SimilarityMetric(
                    "longestCommonSubsequence",
                    de.unitrier.st.stringsimilarity.edit.Variants::longestCommonSubsequence,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "longestCommonSubsequenceNormalized",
                    de.unitrier.st.stringsimilarity.edit.Variants::longestCommonSubsequenceNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );


            // ****** Fingerprint based *****

            similarityMetrics.add(new SimilarityMetric(
                    "winnowingTwoGramJaccard",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramJaccard,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "winnowingThreeGramJaccard",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramJaccard,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "winnowingFourGramJaccard",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramJaccard,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "winnowingFiveGramJaccard",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramJaccard,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );

            similarityMetrics.add(new SimilarityMetric(
                    "winnowingTwoGramJaccardNormalized",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramJaccardNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "winnowingThreeGramJaccardNormalized",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramJaccardNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "winnowingFourGramJaccardNormalized",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramJaccardNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );
            similarityMetrics.add(new SimilarityMetric(
                    "winnowingFiveGramJaccardNormalized",
                    de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramJaccardNormalized,
                    SimilarityMetric.MetricType.EDIT,
                    similarityThreshold)
            );

            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramDice);
            //        similarityMetricsNames.add("winnowingTwoGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramDice);
            //        similarityMetricsNames.add("winnowingThreeGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramDice);
            //        similarityMetricsNames.add("winnowingFourGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramDice);
            //        similarityMetricsNames.add("winnowingFiveGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramDiceNormalized);
            //        similarityMetricsNames.add("winnowingTwoGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramDiceNormalized);
            //        similarityMetricsNames.add("winnowingThreeGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramDiceNormalized);
            //        similarityMetricsNames.add("winnowingFourGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramDiceNormalized);
            //        similarityMetricsNames.add("winnowingFiveGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOverlap);
            //        similarityMetricsNames.add("winnowingTwoGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOverlap);
            //        similarityMetricsNames.add("winnowingThreeGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOverlap);
            //        similarityMetricsNames.add("winnowingFourGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOverlap);
            //        similarityMetricsNames.add("winnowingFiveGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOverlapNormalized);
            //        similarityMetricsNames.add("winnowingTwoGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOverlapNormalized);
            //        similarityMetricsNames.add("winnowingThreeGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOverlapNormalized);
            //        similarityMetricsNames.add("winnowingFourGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOverlapNormalized);
            //        similarityMetricsNames.add("winnowingFiveGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramLongestCommonSubsequence);
            //        similarityMetricsNames.add("winnowingTwoGramLongestCommonSubsequence");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramLongestCommonSubsequence);
            //        similarityMetricsNames.add("winnowingThreeGramLongestCommonSubsequence");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramLongestCommonSubsequence);
            //        similarityMetricsNames.add("winnowingFourGramLongestCommonSubsequence");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramLongestCommonSubsequence);
            //        similarityMetricsNames.add("winnowingFiveGramLongestCommonSubsequence");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramLongestCommonSubsequenceNormalized);
            //        similarityMetricsNames.add("winnowingTwoGramLongestCommonSubsequenceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramLongestCommonSubsequenceNormalized);
            //        similarityMetricsNames.add("winnowingThreeGramLongestCommonSubsequenceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramLongestCommonSubsequenceNormalized);
            //        similarityMetricsNames.add("winnowingFourGramLongestCommonSubsequenceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramLongestCommonSubsequenceNormalized);
            //        similarityMetricsNames.add("winnowingFiveGramLongestCommonSubsequenceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOptimalAlignment);
            //        similarityMetricsNames.add("winnowingTwoGramOptimalAlignment");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOptimalAlignment);
            //        similarityMetricsNames.add("winnowingThreeGramOptimalAlignment");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOptimalAlignment);
            //        similarityMetricsNames.add("winnowingFourGramOptimalAlignment");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOptimalAlignment);
            //        similarityMetricsNames.add("winnowingFiveGramOptimalAlignment");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingTwoGramOptimalAlignmentNormalized);
            //        similarityMetricsNames.add("winnowingTwoGramOptimalAlignmentNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingThreeGramOptimalAlignmentNormalized);
            //        similarityMetricsNames.add("winnowingThreeGramOptimalAlignmentNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFourGramOptimalAlignmentNormalized);
            //        similarityMetricsNames.add("winnowingFourGramOptimalAlignmentNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.fingerprint.Variants::winnowingFiveGramOptimalAlignmentNormalized);
            //        similarityMetricsNames.add("winnowingFiveGramOptimalAlignmentNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.FINGERPRINT);
            //
            //        // ****** Profile based *****
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTokenNormalizedBool);
            //        similarityMetricsNames.add("cosineTokenNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTokenNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineTokenNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTokenNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineTokenNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoGramNormalizedBool);
            //        similarityMetricsNames.add("cosineTwoGramNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeGramNormalizedBool);
            //        similarityMetricsNames.add("cosineThreeGramNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFourGramNormalizedBool);
            //        similarityMetricsNames.add("cosineFourGramNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFiveGramNormalizedBool);
            //        similarityMetricsNames.add("cosineFiveGramNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoGramNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineTwoGramNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeGramNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineThreeGramNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFourGramNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineFourGramNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFiveGramNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineFiveGramNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoGramNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineTwoGramNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeGramNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineThreeGramNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFourGramNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineFourGramNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineFiveGramNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineFiveGramNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoShingleNormalizedBool);
            //        similarityMetricsNames.add("cosineTwoShingleNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeShingleNormalizedBool);
            //        similarityMetricsNames.add("cosineThreeShingleNormalizedBool");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoShingleNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineTwoShingleNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeShingleNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineThreeShingleNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineTwoShingleNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineTwoShingleNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::cosineThreeShingleNormalizedNormalizedTermFrequency);
            //        similarityMetricsNames.add("cosineThreeShingleNormalizedNormalizedTermFrequency");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanTokenNormalized);
            //        similarityMetricsNames.add("manhattanTokenNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanTwoGramNormalized);
            //        similarityMetricsNames.add("manhattanTwoGramNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanThreeGramNormalized);
            //        similarityMetricsNames.add("manhattanThreeGramNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanFourGramNormalized);
            //        similarityMetricsNames.add("manhattanFourGramNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanFiveGramNormalized);
            //        similarityMetricsNames.add("manhattanFiveGramNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanTwoShingleNormalized);
            //        similarityMetricsNames.add("manhattanTwoShingleNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.profile.Variants::manhattanThreeShingleNormalized);
            //        similarityMetricsNames.add("manhattanThreeShingleNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.PROFILE);
            //
            //        // ****** Set based *****
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenEquals);
            //        similarityMetricsNames.add("tokenEquals");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenEqualsNormalized);
            //        similarityMetricsNames.add("tokenEqualsNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenJaccard);
            //        similarityMetricsNames.add("tokenJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenJaccardNormalized);
            //        similarityMetricsNames.add("tokenJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramJaccard);
            //        similarityMetricsNames.add("twoGramJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramJaccard);
            //        similarityMetricsNames.add("threeGramJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramJaccard);
            //        similarityMetricsNames.add("fourGramJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramJaccard);
            //        similarityMetricsNames.add("fiveGramJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramJaccardNormalized);
            //        similarityMetricsNames.add("twoGramJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramJaccardNormalized);
            //        similarityMetricsNames.add("threeGramJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramJaccardNormalized);
            //        similarityMetricsNames.add("fourGramJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramJaccardNormalized);
            //        similarityMetricsNames.add("fiveGramJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramJaccardNormalizedPadding);
            //        similarityMetricsNames.add("twoGramJaccardNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramJaccardNormalizedPadding);
            //        similarityMetricsNames.add("threeGramJaccardNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramJaccardNormalizedPadding);
            //        similarityMetricsNames.add("fourGramJaccardNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramJaccardNormalizedPadding);
            //        similarityMetricsNames.add("fiveGramJaccardNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleJaccard);
            //        similarityMetricsNames.add("twoShingleJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleJaccard);
            //        similarityMetricsNames.add("threeShingleJaccard");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleJaccardNormalized);
            //        similarityMetricsNames.add("twoShingleJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleJaccardNormalized);
            //        similarityMetricsNames.add("threeShingleJaccardNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenDice);
            //        similarityMetricsNames.add("tokenDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenDiceNormalized);
            //        similarityMetricsNames.add("tokenDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramDice);
            //        similarityMetricsNames.add("twoGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramDice);
            //        similarityMetricsNames.add("threeGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramDice);
            //        similarityMetricsNames.add("fourGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramDice);
            //        similarityMetricsNames.add("fiveGramDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramDiceNormalized);
            //        similarityMetricsNames.add("twoGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramDiceNormalized);
            //        similarityMetricsNames.add("threeGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramDiceNormalized);
            //        similarityMetricsNames.add("fourGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramDiceNormalized);
            //        similarityMetricsNames.add("fiveGramDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramDiceNormalizedPadding);
            //        similarityMetricsNames.add("twoGramDiceNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramDiceNormalizedPadding);
            //        similarityMetricsNames.add("threeGramDiceNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramDiceNormalizedPadding);
            //        similarityMetricsNames.add("fourGramDiceNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramDiceNormalizedPadding);
            //        similarityMetricsNames.add("fiveGramDiceNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleDice);
            //        similarityMetricsNames.add("twoShingleDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleDice);
            //        similarityMetricsNames.add("threeShingleDice");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleDiceNormalized);
            //        similarityMetricsNames.add("twoShingleDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleDiceNormalized);
            //        similarityMetricsNames.add("threeShingleDiceNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenOverlap);
            //        similarityMetricsNames.add("tokenOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::tokenOverlapNormalized);
            //        similarityMetricsNames.add("tokenOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramOverlap);
            //        similarityMetricsNames.add("twoGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramOverlap);
            //        similarityMetricsNames.add("threeGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramOverlap);
            //        similarityMetricsNames.add("fourGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramOverlap);
            //        similarityMetricsNames.add("fiveGramOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramOverlapNormalized);
            //        similarityMetricsNames.add("twoGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramOverlapNormalized);
            //        similarityMetricsNames.add("threeGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramOverlapNormalized);
            //        similarityMetricsNames.add("fourGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramOverlapNormalized);
            //        similarityMetricsNames.add("fiveGramOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoGramOverlapNormalizedPadding);
            //        similarityMetricsNames.add("twoGramOverlapNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeGramOverlapNormalizedPadding);
            //        similarityMetricsNames.add("threeGramOverlapNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fourGramOverlapNormalizedPadding);
            //        similarityMetricsNames.add("fourGramOverlapNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::fiveGramOverlapNormalizedPadding);
            //        similarityMetricsNames.add("fiveGramOverlapNormalizedPadding");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleOverlap);
            //        similarityMetricsNames.add("twoShingleOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleOverlap);
            //        similarityMetricsNames.add("threeShingleOverlap");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::twoShingleOverlapNormalized);
            //        similarityMetricsNames.add("twoShingleOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
            //        similarityMetrics.add(de.unitrier.st.stringsimilarity.set.Variants::threeShingleOverlapNormalized);
            //        similarityMetricsNames.add("threeShingleOverlapNormalized");
            //        similarityMetricsTypes.add(MetricComparison.MetricType.SET);
        }
    }
}