/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.apps.googleads;

import com.google.common.base.Strings;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.app.etl.ETLTestBase;
import io.cdap.cdap.datapipeline.SmartWorkflow;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSource;
import io.cdap.cdap.etl.proto.ArtifactSelectorConfig;
import io.cdap.cdap.etl.proto.v2.ETLBatchConfig;
import io.cdap.cdap.etl.proto.v2.ETLPlugin;
import io.cdap.cdap.etl.proto.v2.ETLStage;
import io.cdap.cdap.proto.ProgramRunStatus;
import io.cdap.cdap.proto.artifact.AppRequest;
import io.cdap.cdap.proto.id.ApplicationId;
import io.cdap.cdap.test.ApplicationManager;
import io.cdap.cdap.test.WorkflowManager;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class GoogleAdsTest extends ETLTestBase {

  public static String TMP_TESTFILE = "/tmp/googleadstestfolder";
  private static String refreshToken;
  private static String clientId;
  private static String clientSecret;
  private static String developerToken;
  private static String clientCustomerId;

  protected static final ArtifactSelectorConfig GOOGLE_ADS_ARTIFACT =
    new ArtifactSelectorConfig("SYSTEM", "google-ads", "[0.0.0, 100.0.0)");

  @BeforeClass
  public static void setupTestClass() throws Exception {
    // initialize api
    refreshToken = System.getProperty("gads.refresh.token");
    if (Strings.isNullOrEmpty(refreshToken)) {
      throw new IllegalArgumentException("gads.refresh.token system property must not be empty.");
    }
    clientId = System.getProperty("gads.client.id");
    if (Strings.isNullOrEmpty(clientId)) {
      throw new IllegalArgumentException("gads.client.id system property must not be empty.");
    }
    clientSecret = System.getProperty("gads.client.secret");
    if (Strings.isNullOrEmpty(clientSecret)) {
      throw new IllegalArgumentException("gads.client.secret system property must not be empty.");
    }
    developerToken = System.getProperty("gads.developer.token");
    if (Strings.isNullOrEmpty(developerToken)) {
      throw new IllegalArgumentException("gads.developer.token system property must not be empty.");
    }
    clientCustomerId = System.getProperty("gads.customer.id");
    if (Strings.isNullOrEmpty(clientCustomerId)) {
      throw new IllegalArgumentException("gads.customer.id system property must not be empty.");
    }
  }

  @After
  public void removeFolders() throws IOException {
    FileUtils.deleteDirectory(new File(TMP_TESTFILE));
  }

  @Test
  public void testSingleReport() throws Exception {
    Map<String, String> sourceProps = getSingleReportConfigs();
    Map<String, String> sinkProp = getFileSinkProp();

    // kv table to kv table pipeline
    ETLStage source = new ETLStage("GoogleAdsBatchSource",
                                   new ETLPlugin("GoogleAdsBatchSource",
                                                 BatchSource.PLUGIN_TYPE,
                                                 sourceProps,
                                                 GOOGLE_ADS_ARTIFACT));

    ETLStage sink = new ETLStage("sink",
                                 new ETLPlugin("File",
                                               BatchSink.PLUGIN_TYPE,
                                               sinkProp));
    ETLBatchConfig etlConfig = ETLBatchConfig.builder()
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = getBatchAppRequestV2(etlConfig);
    ApplicationId appId = TEST_NAMESPACE.app("testapp");
    ApplicationManager applicationManager = deployApplication(appId, appRequest);


    WorkflowManager workflowManager = applicationManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    File file = new File(TMP_TESTFILE);
    Assert.assertTrue(file.isDirectory());
    File[] files = file.listFiles();
    Assert.assertEquals(1, files.length);
    File resultDir = files[0];
    Assert.assertTrue(resultDir.isDirectory());
    Assert.assertTrue(new File(resultDir, "_SUCCESS").exists());
    File[] resulFiles = resultDir.listFiles();
    Assert.assertEquals(4, resulFiles.length); // (1 report + 1 _SUCCESS ) * 2 .crc = 4 files
  }

  @Test
  public void testMultiReport() throws Exception {
    Map<String, String> sourceProps = getMultiReportConfigs();
    Map<String, String> sinkProp = getFileSinkProp();

    // kv table to kv table pipeline
    ETLStage source = new ETLStage("GoogleAdsMultiReportBatchSource",
                                   new ETLPlugin("GoogleAdsMultiReportBatchSource",
                                                 BatchSource.PLUGIN_TYPE,
                                                 sourceProps,
                                                 GOOGLE_ADS_ARTIFACT));

    ETLStage sink = new ETLStage("sink",
                                 new ETLPlugin("File",
                                               BatchSink.PLUGIN_TYPE,
                                               sinkProp));
    ETLBatchConfig etlConfig = ETLBatchConfig.builder()
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = getBatchAppRequestV2(etlConfig);
    ApplicationId appId = TEST_NAMESPACE.app("testapp");
    ApplicationManager applicationManager = deployApplication(appId, appRequest);


    WorkflowManager workflowManager = applicationManager.getWorkflowManager(SmartWorkflow.NAME);
    workflowManager.start();
    workflowManager.waitForRun(ProgramRunStatus.COMPLETED, 5, TimeUnit.MINUTES);

    File file = new File(TMP_TESTFILE);
    Assert.assertTrue(file.isDirectory());
    File[] files = file.listFiles();
    Assert.assertEquals(1, files.length);
    File resultDir = files[0];
    Assert.assertTrue(resultDir.isDirectory());
    Assert.assertTrue(new File(resultDir, "_SUCCESS").exists());
    File[] resulFiles = resultDir.listFiles();
    Assert.assertEquals(176, resulFiles.length); // (87 reports + 1 _SUCCESS ) * 2 .crc = 176 files
  }

  public Map<String, String> getSourceMinimalDefaultConfigs() {
    Map<String, String> sourceProps = new HashMap<>();
    sourceProps.put("referenceName", "ref");
    sourceProps.put("refreshToken", refreshToken);
    sourceProps.put("clientId", clientId);
    sourceProps.put("clientSecret", clientSecret);
    sourceProps.put("developerToken", developerToken);
    sourceProps.put("clientCustomerId", clientCustomerId);
    sourceProps.put("startDate", "TODAY");
    sourceProps.put("endDate", "TODAY");
    sourceProps.put("includeReportSummary", "true");
    sourceProps.put("useRawEnumValues", "true");
    sourceProps.put("includeZeroImpressions", "false");
    return sourceProps;

  }

  public Map<String, String> getMultiReportConfigs() {
    Map<String, String> sourceProps = getSourceMinimalDefaultConfigs();
    sourceProps.put("reportFormat", "CSV");
    sourceProps.put("includeReportHeader", "true");
    sourceProps.put("includeColumnHeader", "true");
    return sourceProps;
  }

  public Map<String, String> getFileSinkProp() {

    Set<Schema.Field> schemaFields = new HashSet<>();
    schemaFields.add(Schema.Field.of("report_name", Schema.nullableOf(Schema.of(Schema.Type.STRING))));
    schemaFields.add(Schema.Field.of("report", Schema.nullableOf(Schema.of(Schema.Type.STRING))));
    Schema fileSchema = Schema.recordOf(
      "GoogleAdsReports",
      schemaFields);
    Map<String, String> properties = new HashMap<>();
    properties.put("suffix", "yyyy-MM-dd-HH-mm");
    properties.put("path", TMP_TESTFILE);
    properties.put("referenceName", "fileref");
    properties.put("format", "json");
    return properties;
  }

  public Map<String, String> getSingleReportConfigs() {
    Map<String, String> sourceProps = getSourceMinimalDefaultConfigs();
    sourceProps.put("reportType", "Account Performance Report: Customer table");
    return sourceProps;
  }
}
