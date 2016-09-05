/*
 * (C) Copyright 2016 NUBOMEDIA (http://www.nubomedia.eu)
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */

package eu.nubomedia.benchmark;

import static org.kurento.commons.PropertiesManager.getProperty;

import java.io.File;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;
import org.kurento.test.base.BrowserTest;
import org.kurento.test.browser.Browser;
import org.kurento.test.browser.BrowserType;
import org.kurento.test.browser.WebPage;
import org.kurento.test.config.BrowserConfig;
import org.kurento.test.config.BrowserScope;
import org.kurento.test.config.TestScenario;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * NUBOMEDIA test.
 *
 * @author Boni Garcia (boni.garcia@urjc.es)
 * @since 6.5.1
 */
public class NubomediaBenchmarkTest extends BrowserTest<WebPage> {

  private final Logger log = LoggerFactory.getLogger(NubomediaBenchmarkTest.class);

  public static final String APP_URL_PROP = "app.url";
  public static final String APP_URL_DEFAULT = "https://localhost:8443/";
  public static final String FAKE_CLIENTS_NUMBER_PROP = "fake.clients.number";
  public static final int FAKE_CLIENTS_NUMBER_DEFAULT = 10;
  public static final String FAKE_CLIENTS_RATE_PROP = "fake.clients.rate";
  public static final int FAKE_CLIENTS_RATE_DEFAULT = 1000;
  public static final String FAKE_CLIENTS_PER_KMS_PROP = "fake.clients.number.per.kms";
  public static final int FAKE_CLIENTS_PER_KMS_DEFAULT = 75;

  public static final String SESSION_PLAYTIME_PROP = "session.play.time";
  public static final int SESSION_PLAYTIME_DEFAULT = 5;
  public static final String SESSION_RATE_PROP = "session.rate.time";
  public static final int SESSION_RATE_DEFAULT = 1000;
  public static final String SESSIONS_NUMBER_PROP = "sessions.number";
  public static final int SESSIONS_NUMBER_DEFAULT = 1;
  public static final String POINTS_PER_SESSION_PROP = "points.per.session";
  public static final int POINTS_PER_SESSION_DEFAULT = 200;
  public static final String MEDIA_PROCESSING_PROP = "processing";
  public static final String MEDIA_PROCESSING_DEFAULT = "None";
  public static final String FAKE_CLIENTS_REMOVE_PROP = "fake.clients.remove";
  public static final boolean FAKE_CLIENTS_REMOVE_DEFAULT = false;
  public static final String FAKE_CLIENTS_TOGETHER_TIME_PROP = "fake.clients.play.time";
  public static final int FAKE_CLIENTS_TOGETHER_TIME_DEFAULT =
      FAKE_CLIENTS_NUMBER_DEFAULT * (FAKE_CLIENTS_RATE_DEFAULT / 1000);
  public static final String FAKE_CLIENTS_KMS_POINTS_PROP = "fake.clients.kms.points";
  public static final int FAKE_CLIENTS_KMS_POINTS_DEFAULT = 200;
  public static final String RATE_KMS_LATENCY_PROP = "rate.kms.latency";
  public static final int RATE_KMS_LATENCY_DEFAULT = 1000;
  public static final String VIDEO_QUALITY_SSIM_PROP = "video.quality.ssim";
  public static final boolean VIDEO_QUALITY_SSIM_DEFAULT = false;
  public static final String VIDEO_QUALITY_PSNR_PROP = "video.quality.psnr";
  public static final boolean VIDEO_QUALITY_PSNR_DEFAULT = false;
  public static final String OUTPUT_FOLDER_PROP = "output.folder";
  public static final String OUTPUT_FOLDER_DEFAULT = ".";
  public static final String SERIALIZE_DATA_PROP = "serialize.data";
  public static final boolean SERIALIZE_DATA_DEFAULT = false;
  public static final String BANDWIDTH_PROP = "webrtc.endpoint.kbps";
  public static final int BANDWIDTH_DEFAULT = 500;

  public int extraTimePerFakeClients = 0;
  public boolean getSsim = getProperty(VIDEO_QUALITY_SSIM_PROP, VIDEO_QUALITY_SSIM_DEFAULT);
  public boolean getPsnr = getProperty(VIDEO_QUALITY_PSNR_PROP, VIDEO_QUALITY_PSNR_DEFAULT);
  public String outputFolder = getProperty(OUTPUT_FOLDER_PROP, OUTPUT_FOLDER_DEFAULT);
  public boolean serializeData = getProperty(SERIALIZE_DATA_PROP, SERIALIZE_DATA_DEFAULT);

  @Parameters(name = "{index}: {0}")
  public static Collection<Object[]> data() {
    String appUrl = getProperty(APP_URL_PROP, APP_URL_DEFAULT);
    int sessionsNumber = getProperty(SESSIONS_NUMBER_PROP, SESSIONS_NUMBER_DEFAULT);

    TestScenario test = new TestScenario();
    test.addBrowser(BrowserConfig.PRESENTER, new Browser.Builder().browserType(BrowserType.CHROME)
        .numInstances(sessionsNumber).scope(BrowserScope.LOCAL).url(appUrl).build());
    test.addBrowser(BrowserConfig.VIEWER, new Browser.Builder().browserType(BrowserType.CHROME)
        .numInstances(sessionsNumber).scope(BrowserScope.LOCAL).url(appUrl).build());
    return Arrays.asList(new Object[][] { { test } });
  }

  @Before
  public void setup() {
    // Set defaults in all browsers
    int sessionsNumber = getProperty(SESSIONS_NUMBER_PROP, SESSIONS_NUMBER_DEFAULT);
    for (int i = 0; i < sessionsNumber; i++) {
      WebDriver[] webDrivers =
          { getPresenter(i).getBrowser().getWebDriver(), getViewer(i).getBrowser().getWebDriver() };

      for (WebDriver webDriver : webDrivers) {
        // Session number
        WebElement sessionNumberWe = webDriver.findElement(By.id("sessionNumber"));
        sessionNumberWe.clear();
        sessionNumberWe.sendKeys(String.valueOf(i));

        // Points per session
        String pointsPerSession =
            String.valueOf(getProperty(POINTS_PER_SESSION_PROP, POINTS_PER_SESSION_DEFAULT));
        WebElement pointsPerSessionWe = webDriver.findElement(By.id("loadPoints"));
        pointsPerSessionWe.clear();
        pointsPerSessionWe.sendKeys(pointsPerSession);

        // Media processing
        String processing = getProperty(MEDIA_PROCESSING_PROP, MEDIA_PROCESSING_DEFAULT);
        Select processingSelect = new Select(webDriver.findElement(By.id("processing")));
        processingSelect.selectByValue(processing);

        // Number of fake clients
        int fakeClientsInt = getProperty(FAKE_CLIENTS_NUMBER_PROP, FAKE_CLIENTS_NUMBER_DEFAULT);
        String fakeClients = String.valueOf(fakeClientsInt);
        WebElement fakeClientsWe = webDriver.findElement(By.id("fakeClients"));
        fakeClientsWe.clear();
        fakeClientsWe.sendKeys(fakeClients);

        // Rate between clients (milliseconds)
        int timeBetweenClients = getProperty(FAKE_CLIENTS_RATE_PROP, FAKE_CLIENTS_RATE_DEFAULT);
        WebElement timeBetweenClientsWe = webDriver.findElement(By.id("timeBetweenClients"));
        timeBetweenClientsWe.clear();
        timeBetweenClientsWe.sendKeys(String.valueOf(timeBetweenClients));

        // Rate KMS latency
        int rateKmsLatency = getProperty(RATE_KMS_LATENCY_PROP, RATE_KMS_LATENCY_DEFAULT);
        WebElement rateKmsLatencyWe = webDriver.findElement(By.id("rateKmsLatency"));
        rateKmsLatencyWe.clear();
        rateKmsLatencyWe.sendKeys(String.valueOf(rateKmsLatency));

        if (fakeClientsInt > 0) {
          extraTimePerFakeClients = fakeClientsInt * timeBetweenClients / 1000;
        }

        // Remove fake clients
        boolean removeFakeClients =
            getProperty(FAKE_CLIENTS_REMOVE_PROP, FAKE_CLIENTS_REMOVE_DEFAULT);
        List<WebElement> removeFakeClientsList =
            webDriver.findElements(By.name("removeFakeClients"));
        int index = removeFakeClients ? 0 : 1;
        removeFakeClientsList.get(index).click();

        // Time with all fake clients together (seconds)
        if (removeFakeClients) {
          int playTime =
              getProperty(FAKE_CLIENTS_TOGETHER_TIME_PROP, FAKE_CLIENTS_TOGETHER_TIME_DEFAULT);
          WebElement playTimeWe = webDriver.findElement(By.id("playTime"));
          playTimeWe.clear();
          playTimeWe.sendKeys(String.valueOf(playTime));

          extraTimePerFakeClients = (extraTimePerFakeClients * 2) + playTime;
        }

        // KMS points for fake clients
        String fakePoints = String
            .valueOf(getProperty(FAKE_CLIENTS_KMS_POINTS_PROP, FAKE_CLIENTS_KMS_POINTS_DEFAULT));
        WebElement fakePointsWe = webDriver.findElement(By.id("fakePoints"));
        fakePointsWe.clear();
        fakePointsWe.sendKeys(fakePoints);

        // Number of fake clients per KMS instance
        String fakeClientsPerInstance =
            String.valueOf(getProperty(FAKE_CLIENTS_PER_KMS_PROP, FAKE_CLIENTS_PER_KMS_DEFAULT));
        WebElement fakeClientsPerInstanceWe =
            webDriver.findElement(By.id("fakeClientsPerInstance"));
        fakeClientsPerInstanceWe.clear();
        fakeClientsPerInstanceWe.sendKeys(fakeClientsPerInstance);

        // Bandwidth
        String bandwidth = String.valueOf(getProperty(BANDWIDTH_PROP, BANDWIDTH_DEFAULT));
        WebElement bandwidthWe = webDriver.findElement(By.id("bandwidth"));
        bandwidthWe.clear();
        bandwidthWe.sendKeys(bandwidth);
      }
    }

    if (!outputFolder.endsWith(File.separator)) {
      outputFolder += File.separator;
    }
  }

  @Test
  public void test() throws Exception {
    final int sessionsNumber = getProperty(SESSIONS_NUMBER_PROP, SESSIONS_NUMBER_DEFAULT);
    final int sessionPlayTime = getProperty(SESSION_PLAYTIME_PROP, SESSION_PLAYTIME_DEFAULT);
    final int sessionRateTime = getProperty(SESSION_RATE_PROP, SESSION_RATE_DEFAULT);

    final CountDownLatch latch = new CountDownLatch(sessionsNumber);
    ExecutorService executor = Executors.newFixedThreadPool(sessionsNumber);
    for (int i = 0; i < sessionsNumber; i++) {
      if (i != 0) {
        waitMilliSeconds(sessionRateTime);
      }
      final int j = i;
      executor.execute(new Runnable() {
        @Override
        public void run() {
          try {
            exercise(j, sessionsNumber, sessionPlayTime, sessionRateTime);
          } catch (Exception e) {
            log.error("Exception in session {}", j, e);
          } finally {
            latch.countDown();
          }
        }
      });
    }
    latch.await();
    executor.shutdown();
  }

  public void exercise(int index, int sessionsNumber, int sessionPlayTime, int sessionRateTime)
      throws Exception {
    log.info("[Session {}] Starting test", index);

    // Sync presenter and viewer time
    log.info("[Session {}] Synchronizing presenter and viewer ... please wait", index);
    WebPage[] browsers = { getPresenter(index), getViewer(index) };
    String[] videoTags = { "video", "video" };
    String[] peerConnections = { "webRtcPeer.peerConnection", "webRtcPeer.peerConnection" };
    syncTimeForOcr(browsers, videoTags, peerConnections);

    // Start presenter
    getPresenter(index).getBrowser().getWebDriver().findElement(By.id("presenter")).click();
    getPresenter(index).subscribeEvent("video", "playing");
    getPresenter(index).waitForEvent("playing");

    // Start viewer
    getViewer(index).getBrowser().getWebDriver().findElement(By.id("viewer")).click();
    getViewer(index).subscribeEvent("video", "playing");
    getViewer(index).waitForEvent("playing");

    log.info("[Session {}] Media in presenter and viewer, starting OCR and recording", index);

    // Start OCR
    getPresenter(index).startOcr();
    getViewer(index).startOcr();

    // Start recordings
    getPresenter(index).startRecording("webRtcPeer.peerConnection.getLocalStreams()[0]");
    getViewer(index).startRecording("webRtcPeer.peerConnection.getRemoteStreams()[0]");

    // Play video
    int playTime = ((sessionsNumber - index - 1) * sessionRateTime / 1000) + sessionPlayTime
        + extraTimePerFakeClients;
    log.info("[Session {}] Total play time {} seconds (extra time because of fake clients {})",
        index, playTime, extraTimePerFakeClients);
    waitSeconds(playTime);

    // Get OCR results and statistics
    log.info("[Session {}] Get OCR results and statistics", index);
    Map<String, Map<String, Object>> presenterMap = getPresenter(index).getOcrMap();
    Map<String, Map<String, Object>> viewerMap = getViewer(index).getOcrMap();

    // Stop recordings
    log.info("[Session {}] Stop recordings", index);
    getPresenter(index).stopRecording();
    getViewer(index).stopRecording();

    // Serialize data
    if (serializeData) {
      log.info("[Session {}] Serialize data", index);
      serializeObject(presenterMap, outputFolder + "presenter.ser");
      serializeObject(viewerMap, outputFolder + "viewer.ser");
    }

    // Finish OCR
    log.info("[Session {}] Finish OCR", index);
    getPresenter(index).endOcr();
    getViewer(index).endOcr();

    // Store recordings
    log.info("[Session {}] Store recordings", index);
    String presenterRecName = "presenter-session" + index + ".webm";
    String viewerRecName = "viewer-session" + index + ".webm";
    File presenterFileRec = getPresenter(index).getRecording(outputFolder + presenterRecName);
    File viewerFileRec = getViewer(index).getRecording(outputFolder + viewerRecName);
    // Uncomment this line to use alternative recording method (useful for long recording)
    // File presenterFileRec = getPresenter(index).saveRecordingToDisk(presenterRecName);
    // File viewerFileRec = getViewer(index).saveRecordingToDisk(viewerRecName);

    // Stop presenter and viewer(s)
    log.info("[Session {}] Stop presenter and viewer(s)", index);
    getPresenter(index).getBrowser().getWebDriver().findElement(By.id("stop")).click();
    getViewer(index).getBrowser().getWebDriver().findElement(By.id("stop")).click();

    // Get latencies from KMS (media pipeline and filter)
    Multimap<String, Object> mediaPipelineLatencies = getLatencies(getViewer(index).getBrowser(),
        "mediaPipelineLatencies", "pipelineLatencyMicroSec");
    Multimap<String, Object> filterLatencies =
        getLatencies(getViewer(index).getBrowser(), "filterLatencies", "filterLatencyMicroSec");

    // Close browsers
    log.info("[Session {}] Close browsers", index);
    getPresenter(index).close();
    getViewer(index).close();

    // Get E2E latency and statistics
    log.info("[Session {}] Calculating latency and collecting stats", index);
    Table<Integer, Integer, String> csvTable = processOcrAndStats(presenterMap, viewerMap);

    // Add media pipeline and filter latencies to result table
    int columnIndex = 1;
    addColumnsToTable(csvTable, mediaPipelineLatencies, columnIndex);
    if (!filterLatencies.values().isEmpty()) {
      columnIndex++;
      addColumnsToTable(csvTable, filterLatencies, columnIndex);
    }

    // Get quality metrics (SSIM, PSNR)
    if (getSsim) {
      log.info("[Session {}] Calculating quality of video (SSIM)", index);
      Multimap<String, Object> ssim = getSsim(presenterFileRec, viewerFileRec);
      columnIndex++;
      addColumnsToTable(csvTable, ssim, columnIndex);
    }
    if (getPsnr) {
      log.info("[Session {}] Calculating quality of video (PSNR)", index);
      Multimap<String, Object> psnr = getPsnr(presenterFileRec, viewerFileRec);
      columnIndex++;
      addColumnsToTable(csvTable, psnr, columnIndex);
    }

    // Write CSV
    String outputCsvFile =
        outputFolder + this.getClass().getSimpleName() + "-session" + index + ".csv";
    writeCSV(outputCsvFile, csvTable);

    log.info("[Session {}] End of test", index);
  }

  private Multimap<String, Object> getLatencies(Browser browser, String jsVarName,
      String headerName) {
    String latenciesStr = (String) browser.executeScriptAndWaitOutput("return " + jsVarName + ";");
    Type listType = new TypeToken<List<Object>>() {
    }.getType();
    List<Object> latenciesList = new Gson().fromJson(latenciesStr, listType);
    Multimap<String, Object> latenciesMultimap = ArrayListMultimap.create();
    latenciesMultimap.putAll(headerName, latenciesList);
    return latenciesMultimap;
  }

}
