package il.co.topq.report.plugins.elastic;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StopWatch;

import il.co.topq.difido.PersistenceUtils;
import il.co.topq.difido.model.Enums.ElementType;
import il.co.topq.difido.model.Enums.Status;
import il.co.topq.difido.model.execution.MachineNode;
import il.co.topq.difido.model.execution.Node;
import il.co.topq.difido.model.execution.NodeWithChildren;
import il.co.topq.difido.model.execution.ScenarioNode;
import il.co.topq.difido.model.execution.TestNode;
import il.co.topq.difido.model.test.ReportElement;
import il.co.topq.difido.model.test.TestDetails;
import il.co.topq.report.Configuration;
import il.co.topq.report.business.elastic.ElasticsearchTest;
import il.co.topq.report.business.execution.ExecutionMetadata;
import il.co.topq.report.events.ExecutionEndedEvent;
import il.co.topq.report.plugins.ElasticPluginController;
import il.co.topq.report.plugins.ExecutionPlugin;

/**
 * this plugin will facilitate communication between the report server and elastic,
 * enabling us to insert custom behavior when writing to elastic.
 * Before using this plugin we should disable elastic in difido configuration
 * as having both elastic integration on and this plugin will result in writing to elastic twice.
 * 
 * @author angel
 *
 */
public class ElasticFilterParametersPlugin implements ExecutionPlugin {
	private static final int TIME_BEFORE_ATTEMPTING_TO_WRITE_TO_ELASTIC_AGAIN = 600;
	private static final ElasticPluginController elasticPluginController = new ElasticPluginController();
	private static final String EXECUTION_JS_FILE_PATTERN = "docRoot\\reports\\%s\\tests\\test_%s";
	private static final Pattern SUB_TEST_NAME_PATTERN = Pattern.compile("subtest:(.*)");
	
	private static final StopWatch lastSuccessfulElasticWrite = new StopWatch();

	@Override
	public String getName() {
		return "ElasticFilterParamsPlugin";
	}

	@Override
	public void execute(List<ExecutionMetadata> metaDataList, String params) {
		// TODO Auto-generated method stub
		
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void onExecutionEnded(ExecutionMetadata metadata) {
		if (metadata == null || metadata.getExecution() == null) return;
		List<ElasticsearchTest> subTests = new LinkedList<>();
		metadata.getExecution().getMachines().forEach(machine -> {
			if (machine.getChildren() == null) return;
			((List)machine.getChildren(false)).forEach(child -> {
				subTests.addAll(handleNode(metadata, machine, (Node)child));
			});
		});
		
		ExecutionEndedEvent executionEndedEvent = new ExecutionEndedEvent(metadata);
		
		//if elastic is disabled (elastic will automatically be disabled if there's a connection failure)
		//and a certain amount of time has passed since the last write attempt, we'll attempt to enable it and reset the count
		//until the next time we attempt to enable it again in case of failure
		if (!ElasticPluginController.enabled &&
			 lastSuccessfulElasticWrite.getTaskCount() > 0 &&
			 lastSuccessfulElasticWrite.getLastTaskInfo().getTimeSeconds() > TIME_BEFORE_ATTEMPTING_TO_WRITE_TO_ELASTIC_AGAIN) {
			ElasticPluginController.enabled = true;
			resetWatch();
		}
		elasticPluginController.onExecutionEndedEvent(executionEndedEvent);
		elasticPluginController.addOrUpdateInElastic(subTests);
		
		resetWatch();
	}

	private void resetWatch() {
		if (lastSuccessfulElasticWrite.getTaskCount() > 0) lastSuccessfulElasticWrite.stop();
		lastSuccessfulElasticWrite.start();
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private List<ElasticsearchTest> handleNode(ExecutionMetadata metadata, MachineNode machine, Node node) {
		if (node instanceof TestNode) {
			TestNode testNode = (TestNode) node;
			Map<String, String> parameters = testNode.getParameters();
			Map<String, String> properties = testNode.getProperties();
			filterIgnoredValues(parameters);
			filterIgnoredValues(properties);
			
			String testFolderPath = String.format(EXECUTION_JS_FILE_PATTERN, metadata.getFolderName(), testNode.getUid());
			testFolderPath = testFolderPath.replaceAll("[\\\\|/]", File.separator);
			File testFolder = new File(testFolderPath);
			TestDetails testDetails = PersistenceUtils.readTest(testFolder);
			List<ElasticsearchTest> elasticTests = new LinkedList<>();
			if (testDetails == null || testDetails.getReportElements() == null) return elasticTests;
			Map<String, List<ReportElement>> subTestsMap = splitToSubTests(testDetails.getReportElements());
			subTestsMap.forEach((testName, reportElements) ->  {
				TestNode subTestNode = TestNode.newInstance(testNode);
				subTestNode.setDate(testNode.getDate());
				subTestNode.setName(testName);
				subTestNode.setUid(UUID.randomUUID().toString());
				//TODO:extract execution time
				ElasticsearchTest elasticsearchTest = elasticPluginController.testNodeToElasticTest(metadata, machine, subTestNode);
				elasticTests.add(elasticsearchTest);
//				TestDetails subTestDetails = new TestDetails();
//				subTestDetails.setReportElements(reportElements);
//				File testDestinationFolder = nextTestFolder(testFolder);
//				PersistenceUtils.writeTest(testDetails, testFolder, testDestinationFolder);
			});
			return elasticTests;
			//TODO:use test details to find sub tests and report them as individual tests


		} else if (node instanceof ScenarioNode){
			ScenarioNode scenarioNode = (ScenarioNode) node;
			Map<String, String> scenarioProperties = scenarioNode.getScenarioProperties();
			filterIgnoredValues(scenarioProperties);
			
		} if (node instanceof NodeWithChildren){
			List<ElasticsearchTest> out = new LinkedList<>();
			((NodeWithChildren<Node>) node).getChildren().forEach(child -> out.addAll(handleNode(metadata, machine, child)));
			return mergeTestsWithSameName(out);
		}
		return null;
	}
	private void filterIgnoredValues(Map<String, String> parameters) {
		if (parameters == null) return;
		List<String> parametersToRemove = new LinkedList<>();
		parameters.keySet()
				  .stream()
				  .filter(key -> isParameterFilteredOutByNamingConvention(key))
				  .forEach(key -> parametersToRemove.add(key));
		parametersToRemove.forEach(key -> parameters.remove(key));
	}
	private Map<String, List<ReportElement>> splitToSubTests(List<ReportElement> reportElements) {
		if (reportElements == null || reportElements.isEmpty()) return Collections.EMPTY_MAP;
		Map<String, List<ReportElement>> output = new HashMap<>();
		List<String> currentTestName = new LinkedList<>();
		reportElements.forEach(reportElement -> {
			if (reportElement.getType() == ElementType.startLevel) {
				String testName = extractSubTestName(reportElement.getTitle());
				if (!StringUtils.isEmpty(testName)) {
					currentTestName.add(testName);
					output.put(testName, new LinkedList<>());
				}
			} else if(reportElement.getType() == ElementType.stopLevel) {
				currentTestName.clear();
			} else if (!currentTestName.isEmpty()) {
				String testName = currentTestName.get(0);
				output.get(testName).add(reportElement);
			}
		});
		return output;
		
	}
	private String extractSubTestName(String title) {
		Matcher matcher = SUB_TEST_NAME_PATTERN.matcher(title);
		String out =  null;
		if (matcher.find()) {
			out = matcher.group(1);
		}
		return out;
	}	
	private boolean isParameterFilteredOutByNamingConvention(String key) {
		return key.toLowerCase().contains("ignore");
	}
	private List<ElasticsearchTest> mergeTestsWithSameName(List<ElasticsearchTest> out) {
		Map<String, ElasticsearchTest> testMap = new HashMap<>();
		out.forEach(testToAdd -> {
			if (testMap.containsKey(testToAdd.getName()))
				testMap.put(testToAdd.getName(), testToAdd);
			else {
				ElasticsearchTest existingTest = testMap.get(testToAdd.getName());
				existingTest.setStatus(worstStatus(existingTest.getStatus(), testToAdd.getStatus()));
				existingTest.setDuration(existingTest.getDuration() + testToAdd.getDuration());
			}				
		});
		return testMap.values().stream().collect(Collectors.toList());
	}

	//evaluate the worse status between 2 statuses, IE: if we have success and a failure, return failure
	private String worstStatus(String status, String status2) {
		if(StringUtils.isEmpty(status) && StringUtils.isEmpty(status2)) return "";
		if (StringUtils.isEmpty(status)) return status2;
		if (StringUtils.isEmpty(status2)) return status;
		if (status.equalsIgnoreCase(status2)) return status;
		return intToStatus(Math.max(statusToInt(status), statusToInt(status2)));
	}

	//convert status string to int by severity (success is the lowest, error the higest)
	private static final int statusToInt(String status) {
		if (status.equalsIgnoreCase(Status.success.toString())) return 0;
		if (status.equalsIgnoreCase(Status.warning.toString())) return 1;
		if (status.equalsIgnoreCase(Status.failure.toString())) return 2;
		if (status.equalsIgnoreCase(Status.error.toString()))   return 3;
		return -1;
	}
	
	//convert status severity int to status string
	private static final String intToStatus(int statusInt) {
		if (statusInt == 0 ) return Status.success.toString();
		if (statusInt == 1 ) return Status.warning.toString();
		if (statusInt == 2 ) return Status.failure.toString();
		if (statusInt == 3 ) return Status.error.toString();
		return "none";
	}

}
