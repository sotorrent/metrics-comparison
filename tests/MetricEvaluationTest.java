import com.google.common.collect.Sets;
import org.sotorrent.metricevaluation.evaluation.MetricEvaluationManager;
import org.sotorrent.metricevaluation.evaluation.MetricEvaluationPerPost;
import org.sotorrent.metricevaluation.evaluation.MetricResult;
import org.sotorrent.metricevaluation.evaluation.SimilarityMetric;
import org.junit.jupiter.api.Test;
import org.sotorrent.posthistoryextractor.Config;
import org.sotorrent.posthistoryextractor.blocks.CodeBlockVersion;
import org.sotorrent.posthistoryextractor.blocks.TextBlockVersion;
import org.sotorrent.posthistoryextractor.gt.PostBlockConnection;
import org.sotorrent.posthistoryextractor.gt.PostGroundTruth;
import org.sotorrent.posthistoryextractor.history.Posts;
import org.sotorrent.posthistoryextractor.version.PostVersionList;
import org.sotorrent.util.LogUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricEvaluationTest {
    private static Logger logger = null;

    static Path pathToComparisonSamplesDir = Paths.get("testdata","samples_comparison");

    static Path pathToPostIdList = Paths.get("testdata", "gt_test", "post_ids.csv");
    static Path pathToPostHistory = Paths.get("testdata", "gt_test", "files");
    static Path pathToGroundTruth = Paths.get("testdata", "gt_test", "gt");

    static Path testOutputDir = Paths.get("testdata", "output");

    static {
        // configure logger
        try {
            logger = LogUtils.getClassLogger(MetricEvaluationTest.class);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Config configEqual = Config.DEFAULT
            .withTextSimilarityMetric(org.sotorrent.stringsimilarity.equal.Variants::equal)
            .withTextBackupSimilarityMetric(null)
            .withTextSimilarityThreshold(1.0)
            .withCodeSimilarityMetric(org.sotorrent.stringsimilarity.equal.Variants::equal)
            .withCodeBackupSimilarityMetric(null)
            .withCodeSimilarityThreshold(1.0);

    @Test
    void testMetricEvaluationManager() {
        MetricEvaluationManager manager = MetricEvaluationManager.DEFAULT
                .withName("TestMetricEvaluationManager")
                .withInputPaths(pathToPostIdList, pathToPostHistory, pathToGroundTruth)
                .withOutputDirPath(testOutputDir)
                .withAllSimilarityMetrics(false)
                .initialize();

        assertEquals(manager.getPostVersionLists().size(), manager.getPostGroundTruths().size());
        assertThat(manager.getPostVersionLists().keySet(), is(manager.getPostGroundTruths().keySet()));

        manager.addSimilarityMetric(
                MetricEvaluationManager.getSimilarityMetric("fourGramOverlap", 0.6)
        );
        manager.addSimilarityMetric(
                MetricEvaluationManager.getSimilarityMetric("levenshteinNormalized", 0.3)
        );

        Thread managerThread = new Thread(manager);
        managerThread.start();
        try {
            managerThread.join();
            assertTrue(manager.isFinished()); // assert that execution of manager successfully finished

            List<Integer> postHistoryIds_3758880 = manager.getPostGroundTruths().get(3758880).getPostHistoryIds();
            MetricEvaluationPerPost evaluation_a_3758880 = manager.getMetricEvaluation(3758880, "fourGramOverlap", 0.6);
            validateAnswer3758880(postHistoryIds_3758880, evaluation_a_3758880);

            List<Integer> postHistoryIds_22037280 = manager.getPostGroundTruths().get(22037280).getPostHistoryIds();
            MetricEvaluationPerPost evaluation_a_22037280 = manager.getMetricEvaluation(22037280, "fourGramOverlap", 0.6);
            validateAnswer22037280(postHistoryIds_22037280, evaluation_a_22037280);

            evaluation_a_3758880 = manager.getMetricEvaluation(3758880, "levenshteinNormalized", 0.3);
            validateAnswer3758880(postHistoryIds_3758880, evaluation_a_3758880);

            evaluation_a_22037280 = manager.getMetricEvaluation(22037280, "levenshteinNormalized", 0.3);
            validateAnswer22037280(postHistoryIds_22037280, evaluation_a_22037280);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void validateAnswer3758880(List<Integer> postHistoryIds_3758880, MetricEvaluationPerPost evaluation_a_3758880) {
        /* validate answer 3758880 */
        // first version has never predecessors
        int postHistoryId = postHistoryIds_3758880.get(0);

        MetricResult resultsText = evaluation_a_3758880.getResultsText(postHistoryId);
        assertEquals(0, resultsText.getTruePositives());
        assertEquals(0, resultsText.getFalsePositives());
        assertEquals(0, resultsText.getTrueNegatives());
        assertEquals(0, resultsText.getFalseNegatives());

        MetricResult resultsCode = evaluation_a_3758880.getResultsCode(postHistoryId);
        assertEquals(0, resultsCode.getTruePositives());
        assertEquals(0, resultsCode.getFalsePositives());
        assertEquals(0, resultsCode.getTrueNegatives());
        assertEquals(0, resultsCode.getFalseNegatives());

        // second version
        postHistoryId = postHistoryIds_3758880.get(1);

        resultsText = evaluation_a_3758880.getResultsText(postHistoryId);
        assertEquals(1, resultsText.getTruePositives());
        assertEquals(0, resultsText.getFalsePositives());
        assertEquals(1, resultsText.getTrueNegatives());
        assertEquals(0, resultsText.getFalseNegatives());

        resultsCode = evaluation_a_3758880.getResultsCode(postHistoryId);
        assertEquals(2, resultsCode.getTruePositives());
        assertEquals(0, resultsCode.getFalsePositives());
        assertEquals(0, resultsCode.getTrueNegatives());
        assertEquals(0, resultsCode.getFalseNegatives());

        // version 3 to 10 only for text blocks (they don't differ)
        for (int i = 2; i < 10; i++) {
            postHistoryId = postHistoryIds_3758880.get(i);
            resultsText = evaluation_a_3758880.getResultsText(postHistoryId);
            assertEquals(2, resultsText.getTruePositives());
            assertEquals(0, resultsText.getFalsePositives());
            assertEquals(0, resultsText.getTrueNegatives());
            assertEquals(0, resultsText.getFalseNegatives());
        }

        postHistoryId = postHistoryIds_3758880.get(10);
        resultsText = evaluation_a_3758880.getResultsText(postHistoryId);
        assertEquals(2, resultsText.getTruePositives());
        assertEquals(0, resultsText.getFalsePositives());
        assertEquals(1, resultsText.getTrueNegatives());
        assertEquals(0, resultsText.getFalseNegatives());

        // versions 3 and 6 for code
        List<Integer> versions = Arrays.asList(2, 5);
        for (Integer version_number : versions) {
            postHistoryId = postHistoryIds_3758880.get(version_number);
            resultsCode = evaluation_a_3758880.getResultsCode(postHistoryId);

            SimilarityMetric metric = evaluation_a_3758880.getSimilarityMetric();
            if (metric.getNameText().equals("levenshteinNormalized")
                    && metric.getNameCode().equals("levenshteinNormalized")
                    && metric.getConfig().getTextSimilarityThreshold() == 0.3
                    && metric.getConfig().getCodeSimilarityThreshold() == 0.3) {
                assertEquals(2, resultsCode.getTruePositives());
                assertEquals(0, resultsCode.getFalsePositives());
                assertEquals(0, resultsCode.getTrueNegatives());
                assertEquals(0, resultsCode.getFalseNegatives());
            } else {
                assertEquals(1, resultsCode.getTruePositives());
                assertEquals(0, resultsCode.getFalsePositives());
                assertEquals(0, resultsCode.getTrueNegatives());
                assertEquals(1, resultsCode.getFalseNegatives());
            }
        }

        // versions 4-5 and 7-11 for code
        versions = Arrays.asList(3, 4, 6, 7, 8, 9, 10);
        for (Integer version_number : versions) {
            postHistoryId = postHistoryIds_3758880.get(version_number);

            resultsCode = evaluation_a_3758880.getResultsCode(postHistoryId);
            assertEquals(2, resultsCode.getTruePositives());
            assertEquals(0, resultsCode.getFalsePositives());
            assertEquals(0, resultsCode.getTrueNegatives());
            assertEquals(0, resultsCode.getFalseNegatives());
        }
    }

    private void validateAnswer22037280(List<Integer> postHistoryIds_22037280, MetricEvaluationPerPost evaluation_a_22037280) {
        /* validate answer 22037280 */
        int postHistoryId = postHistoryIds_22037280.get(0);

        MetricResult resultsText = evaluation_a_22037280.getResultsText(postHistoryId);
        assertEquals(0, resultsText.getTruePositives());
        assertEquals(0, resultsText.getFalsePositives());
        assertEquals(0, resultsText.getTrueNegatives());
        assertEquals(0, resultsText.getFalseNegatives());

        MetricResult resultsCode = evaluation_a_22037280.getResultsCode(postHistoryId);
        assertEquals(0, resultsCode.getTruePositives());
        assertEquals(0, resultsCode.getFalsePositives());
        assertEquals(0, resultsCode.getTrueNegatives());
        assertEquals(0, resultsCode.getFalseNegatives());

        for (int i = 1; i < postHistoryIds_22037280.size(); i++) {
            postHistoryId = postHistoryIds_22037280.get(i);

            SimilarityMetric metric = evaluation_a_22037280.getSimilarityMetric();
            if (i == 2
                    && metric.getNameText().equals("levenshteinNormalized")
                    && metric.getNameCode().equals("levenshteinNormalized")
                    && metric.getConfig().getTextSimilarityThreshold() == 0.3
                    && metric.getConfig().getCodeSimilarityThreshold() == 0.3) {
                resultsText = evaluation_a_22037280.getResultsText(postHistoryId);
                assertEquals(2, resultsText.getTruePositives());
                assertEquals(0, resultsText.getFalsePositives());
                assertEquals(0, resultsText.getTrueNegatives());
                assertEquals(1, resultsText.getFalseNegatives());
            } else {
                resultsText = evaluation_a_22037280.getResultsText(postHistoryId);
                assertEquals(3, resultsText.getTruePositives());
                assertEquals(0, resultsText.getFalsePositives());
                assertEquals(0, resultsText.getTrueNegatives());
                assertEquals(0, resultsText.getFalseNegatives());
            }

            resultsCode = evaluation_a_22037280.getResultsCode(postHistoryId);
            assertEquals(2, resultsCode.getTruePositives());
            assertEquals(0, resultsCode.getFalsePositives());
            assertEquals(0, resultsCode.getTrueNegatives());
            assertEquals(0, resultsCode.getFalseNegatives());
        }
    }

    @Test
    void validateAnswer10381975() {
        int postId = 10381975;

        PostVersionList a_10381975 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.ANSWER_ID, false);
        a_10381975.normalizeLinks();
        a_10381975.processVersionHistory(Config.DEFAULT);
        PostGroundTruth a_10381975_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // text
        validateResults(a_10381975, a_10381975_gt, null, TextBlockVersion.getPostBlockTypeIdFilter(),
                10+10+10+10+9, 0, 9*10 + 9*10 + 9*10 + 9*10 + 9*9, 0);
        // fn: comparison between versions 2 and 3 and connection null <- 17 instead of 17 <- 17 as well as between version 5 and 6 and connection null <- 11 instead of 11 <- 11
    }

    @Test
    void testAggregatedResultsManagers() {
        ExecutorService threadPool = Executors.newFixedThreadPool(4);
        List<MetricEvaluationManager> managers = MetricEvaluationManager.createManagersFromSampleDirectories(
                pathToComparisonSamplesDir, testOutputDir, false,
                Sets.newHashSet(
                        "PostId_VersionCount_SO_17-06_sample_100_1",
                        "PostId_VersionCount_SO_17-06_sample_100_2"
                )
        );

        for (MetricEvaluationManager manager : managers) {
            manager.addSimilarityMetric(
                    MetricEvaluationManager.getSimilarityMetric("winnowingTwoGramOverlap", 0.3)
            );
            manager.addSimilarityMetric(
                    MetricEvaluationManager.getSimilarityMetric("tokenJaccard", 0.6)
            );
            manager.addSimilarityMetric(
                    MetricEvaluationManager.getSimilarityMetric("twoGramJaccard", 0.9)
            );
            // the following metric should produce failed comparisons
            manager.addSimilarityMetric(
                    MetricEvaluationManager.getSimilarityMetric("twoShingleOverlap", 0.6)
            );

            threadPool.execute(new Thread(manager));
        }

        threadPool.shutdown();
        try {
            threadPool.awaitTermination(1, TimeUnit.DAYS);

            for (MetricEvaluationManager manager : managers) {
                assertTrue(manager.isFinished()); // assert that execution of manager successfully finished
            }

            // output file aggregated over all samples
            File outputFileAggregated= Paths.get(testOutputDir.toString(), "MetricComparison_aggregated.csv").toFile();
            if (outputFileAggregated.exists()) {
                if (!outputFileAggregated.delete()) {
                    throw new IllegalStateException("Error while deleting output file: " + outputFileAggregated);
                }
            }

            MetricEvaluationManager.aggregateAndWriteSampleResults(managers, outputFileAggregated);
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            e.printStackTrace();
        }
    }

    @Test
    void testFailedPredecessorComparisons1() {
        MetricEvaluationManager manager = MetricEvaluationManager.DEFAULT
                .withName("TestFailedPredecessorComparisonsText")
                .withInputPaths(pathToPostIdList, pathToPostHistory, pathToGroundTruth)
                .withOutputDirPath(testOutputDir)
                .withAllSimilarityMetrics(false)
                .initialize();

        assertEquals(manager.getPostVersionLists().size(), manager.getPostGroundTruths().size());
        assertThat(manager.getPostVersionLists().keySet(), is(manager.getPostGroundTruths().keySet()));

        manager.addSimilarityMetric(
                MetricEvaluationManager.getSimilarityMetric("threeShingleOverlap", 0.6)
        );

        Thread managerThread = new Thread(manager);
        managerThread.start();
        try {
            managerThread.join();
            assertTrue(manager.isFinished()); // assert that execution of manager successfully finished

            List<Integer> postHistoryIds_3758880 = manager.getPostGroundTruths().get(3758880).getPostHistoryIds();
            MetricEvaluationPerPost evaluation_a_3758880 = manager.getMetricEvaluation(3758880, "threeShingleOverlap", 0.6);

            int postHistoryId_version2 = postHistoryIds_3758880.get(1);

            MetricResult resultsText = evaluation_a_3758880.getResultsText(postHistoryId_version2);
            assertEquals(1, resultsText.getTruePositives());
            assertEquals(0, resultsText.getFalsePositives());
            assertEquals(1, resultsText.getTrueNegatives());
            assertEquals(0, resultsText.getFalseNegatives());
            assertEquals(4, resultsText.getFailedPredecessorComparisons());

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    void testFailedPredecessorComparisons2() {
        MetricEvaluationManager manager = MetricEvaluationManager.DEFAULT
                .withName("TestFailedPredecessorComparisonsCode")
                .withInputPaths(pathToPostIdList, pathToPostHistory, pathToGroundTruth)
                .withOutputDirPath(testOutputDir)
                .withAllSimilarityMetrics(false)
                .initialize();

        assertEquals(manager.getPostVersionLists().size(), manager.getPostGroundTruths().size());
        assertThat(manager.getPostVersionLists().keySet(), is(manager.getPostGroundTruths().keySet()));

        manager.addSimilarityMetric(
                MetricEvaluationManager.getSimilarityMetric("threeShingleOverlap", 0.6)
        );

        Thread managerThread = new Thread(manager);
        managerThread.start();
        try {
            managerThread.join();
            assertTrue(manager.isFinished()); // assert that execution of manager successfully finished

            List<Integer> postHistoryIds_2096370 = manager.getPostGroundTruths().get(2096370).getPostHistoryIds();
            MetricEvaluationPerPost evaluation_a_2096370 = manager.getMetricEvaluation(2096370, "threeShingleOverlap", 0.6);

            int postHistoryId_version2 = postHistoryIds_2096370.get(1);

            MetricResult resultsCode = evaluation_a_2096370.getResultsCode(postHistoryId_version2);
            assertEquals(1, resultsCode.getTruePositives());
            assertEquals(0, resultsCode.getFalsePositives());
            assertEquals(0, resultsCode.getTrueNegatives());
            assertEquals(0, resultsCode.getFalseNegatives());
            assertEquals(0, resultsCode.getFailedPredecessorComparisons());

            MetricResult resultsText = evaluation_a_2096370.getResultsText(postHistoryId_version2);
            assertEquals(1, resultsText.getTruePositives());
            assertEquals(0, resultsText.getFalsePositives());
            assertEquals(0, resultsText.getTrueNegatives());
            assertEquals(1, resultsText.getFalseNegatives());
            // text blocks with local id 3 are only contain two words, thus comparison fails for (1,3) and (3,3)
            assertEquals(2, resultsText.getFailedPredecessorComparisons());

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Test
    void validationTest() {
        MetricEvaluationManager manager = MetricEvaluationManager.DEFAULT
                .withName("ValidationTestSample")
                .withInputPaths(pathToPostIdList, pathToPostHistory, pathToGroundTruth)
                .withValidate(false)
                .initialize();
        assertTrue(manager.validate());
    }

    @Test
    void equalsTestQuestion10381975() {
        int postId = 10381975;
        PostVersionList q_10381975 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.QUESTION_ID, false);
        q_10381975.processVersionHistory(Config.DEFAULT
                .withTextSimilarityMetric(org.sotorrent.stringsimilarity.equal.Variants::equal)
                .withTextBackupSimilarityMetric(null)
                .withTextSimilarityThreshold(1.0)
                .withCodeSimilarityMetric(org.sotorrent.stringsimilarity.equal.Variants::equal)
                .withCodeBackupSimilarityMetric(null)
                .withCodeSimilarityThreshold(1.0)
        );
        PostGroundTruth q_10381975_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // check if the post version list does not contain more connections than the ground truth (which should not
        // happen when using equality-based metrics)
        validateEqualMetricConnections(q_10381975, q_10381975_gt);

        // check if manager produces false positives or failed comparisons
        MetricEvaluationManager manager = MetricEvaluationManager.DEFAULT
                .withName("EqualTestSample")
                .withInputPaths(pathToPostIdList, pathToPostHistory, pathToGroundTruth)
                .withOutputDirPath(testOutputDir)
                .withAllSimilarityMetrics(false)
                .initialize();

        manager.addSimilarityMetric(
                MetricEvaluationManager.getSimilarityMetric("equal", 1.0)
        );

        Thread managerThread = new Thread(manager);
        managerThread.start();
        try {
            managerThread.join();
            assertTrue(manager.isFinished()); // assert that execution of manager successfully finished
            // assert that equality-based metric did not produce false positives or failed comparisons
            validateEqualMetricResults(manager, postId);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void validateEqualMetricConnections(PostVersionList postVersionList, PostGroundTruth postGroundTruth) {
        // check if the post version list does not contain more connections than the ground truth (which should not
        // happen when using equality-based metrics)

        // text
        Set<PostBlockConnection> connectionsList = postVersionList.getConnections(TextBlockVersion.getPostBlockTypeIdFilter());
        Set<PostBlockConnection> connectionsGT = postGroundTruth.getConnections(TextBlockVersion.getPostBlockTypeIdFilter());
        assertEquals(0, PostBlockConnection.difference(connectionsList, connectionsGT).size());

        // code
        connectionsList = postVersionList.getConnections(CodeBlockVersion.getPostBlockTypeIdFilter());
        connectionsGT = postGroundTruth.getConnections(CodeBlockVersion.getPostBlockTypeIdFilter());
        assertEquals(0, PostBlockConnection.difference(connectionsList, connectionsGT).size());
    }

    static void validateEqualMetricResults(MetricEvaluationManager manager, int postId) {
        // assert that equality-based metric did not produce false positives or failed comparisons
        MetricEvaluationPerPost evaluation = manager.getMetricEvaluation(postId, "equal", 1.0);
        for (int postHistoryId : evaluation.getPostHistoryIds()) {
            MetricResult resultsCode = evaluation.getResultsCode(postHistoryId);
            if (resultsCode.getFalsePositives() > 0) {
                logger.warning("False positives for postId " + postId + ", postHistoryId " + postHistoryId);
            }
            assertEquals(0, resultsCode.getFalsePositives());
            assertEquals(0, resultsCode.getFailedPredecessorComparisons());

            MetricResult resultsText = evaluation.getResultsText(postHistoryId);
            if (resultsText.getFalsePositives() > 0) {
                logger.warning("False positives for postId " + postId + ", postHistoryId " + postHistoryId);
            }
            assertEquals(0, resultsText.getFalsePositives());
            assertEquals(0, resultsText.getFailedPredecessorComparisons());
        }
    }

    @Test
    void equalsTestWithoutManagerAnswer10381975() {
        int postId = 10381975;

        PostVersionList a_10381975 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.ANSWER_ID, false);
        a_10381975.normalizeLinks();
        a_10381975.processVersionHistory(configEqual);
        PostGroundTruth a_10381975_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // text
        validateResults(a_10381975, a_10381975_gt, null, TextBlockVersion.getPostBlockTypeIdFilter(),
                10+9+10+10+8, 0, 9*10 + 9*10 + 9*10 + 9*10 + 9*9, 2);
        // fn: comparison between versions 2 and 3 and connection null <- 17 instead of 17 <- 17 as well as between version 5 and 6 and connection null <- 11 instead of 11 <- 11
    }

    @Test
    void equalsTestWithoutManagerAnswer32841902() {
        int postId = 32841902;
        PostVersionList a_32841902 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.ANSWER_ID, false);
        a_32841902.normalizeLinks();
        a_32841902.processVersionHistory(configEqual);
        PostGroundTruth a_32841902_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // code
        validateResults(a_32841902, a_32841902_gt, null, CodeBlockVersion.getPostBlockTypeIdFilter(),
                2, 0, 2*2 + 2, 0);
    }

    @Test
    void equalsTestWithoutManagerQuestion13651791() {
        int postId = 13651791;
        PostVersionList q_13651791 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.QUESTION_ID, false);
        q_13651791.normalizeLinks();
        q_13651791.processVersionHistory(configEqual);
        PostGroundTruth q_13651791_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // code
        validateResults(q_13651791, q_13651791_gt, null, CodeBlockVersion.getPostBlockTypeIdFilter(),
                4, 0, 4*5, 1);
        // tp: 4 of 5 should be right because the code blocks with local ids 2 should not be matched by a metric of type EQUAL that does not normalize
    }

    @Test
    void equalsTestWithoutManagerAnswer33076987() {
        int postId = 33076987;
        PostVersionList a_33076987 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.QUESTION_ID, false);
        a_33076987.normalizeLinks();
        a_33076987.processVersionHistory(configEqual);
        PostGroundTruth a_33076987_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // text
        validateResults(a_33076987, a_33076987_gt, null, TextBlockVersion.getPostBlockTypeIdFilter(),
                2, 0,  2 + 2 + 2, 1);
    }

    @Test
    void equalsTestWithoutManagerAnswer38742394() {
        int postId = 38742394;
        PostVersionList a_38742394 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.QUESTION_ID, false);
        a_38742394.normalizeLinks();
        a_38742394.processVersionHistory(configEqual);
        PostGroundTruth a_38742394_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // code
        validateResults(a_38742394, a_38742394_gt, null, CodeBlockVersion.getPostBlockTypeIdFilter(),
                1, 0, 2 + 3 + 2 + 2, 2);
    }

    @Test
    void equalsTestWithoutManagerAnswer37196630() {
        int postId = 37196630;
        int postHistoryId = 117953545; // version 3
        PostVersionList a_37196630 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.QUESTION_ID, false);
        a_37196630.normalizeLinks();
        a_37196630.processVersionHistory(configEqual);
        PostGroundTruth a_37196630_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // text
        validateResults(a_37196630, a_37196630_gt, postHistoryId, TextBlockVersion.getPostBlockTypeIdFilter(),
                3, 0, 2*4 + 3*4, 1);

        // code
        validateResults(a_37196630, a_37196630_gt, postHistoryId, CodeBlockVersion.getPostBlockTypeIdFilter(),
                3, 0, 2*3 + 3*2, 0);
    }

    @Test
    void equalsTestWithoutManagerQuestion23459881() {
        int postId = 23459881;
        int postHistoryId = 64356224; // version 2
        PostVersionList q_23459881 = PostVersionList.readFromCSV(pathToPostHistory, postId, Posts.QUESTION_ID, false);
        q_23459881.normalizeLinks();
        q_23459881.processVersionHistory(configEqual);
        PostGroundTruth q_23459881_gt = PostGroundTruth.readFromCSV(pathToGroundTruth, postId);

        // text
        validateResults(q_23459881, q_23459881_gt, postHistoryId, TextBlockVersion.getPostBlockTypeIdFilter(),
                3, 0, 3*2 + 3, 0);

        // code
        validateResults(q_23459881, q_23459881_gt, postHistoryId, CodeBlockVersion.getPostBlockTypeIdFilter(),
                3, 0, 2*3 + 3, 0);
    }

    private void validateResults(PostVersionList postVersionList, PostGroundTruth postGroundTruth,
                                 Integer postHistoryId, Set<Byte> postBlockTypeFilter,
                                 int expectedTruePositives, int expectedFalsePositives,
                                 int expectedTrueNegatives, int expectedFalseNegatives) {

        Set<PostBlockConnection> connections;
        Set<PostBlockConnection> connections_gt;

        if (postHistoryId == null) {
            connections = postVersionList.getConnections(postBlockTypeFilter);
            connections_gt = postGroundTruth.getConnections(postBlockTypeFilter);
        } else {
            connections = postVersionList.getPostVersion(postHistoryId).getConnections(postBlockTypeFilter);
            connections_gt = postGroundTruth.getConnections(postHistoryId, postBlockTypeFilter);
        }

        int truePositivesCount = PostBlockConnection.getTruePositives(connections, connections_gt).size();
        assertEquals(expectedTruePositives, truePositivesCount);

        int falsePositivesCount = PostBlockConnection.getFalsePositives(connections, connections_gt).size();
        // equals metric should never have false positives
        assertEquals(expectedFalsePositives, falsePositivesCount);

        int possibleComparisons;
        if (postHistoryId == null) {
            possibleComparisons = postGroundTruth.getPossibleComparisons(postBlockTypeFilter);
        } else {
            possibleComparisons = postGroundTruth.getPossibleComparisons(postHistoryId, postBlockTypeFilter);
        }
        int trueNegativesCount = PostBlockConnection.getTrueNegatives(connections, connections_gt, possibleComparisons);
        assertEquals(expectedTrueNegatives, trueNegativesCount);

        int falseNegativesCount = PostBlockConnection.getFalseNegatives(connections, connections_gt).size();
        assertEquals(expectedFalseNegatives, falseNegativesCount);
    }
}
