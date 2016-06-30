/*
 * (C) Copyright 2016 Boni Garcia (http://bonigarcia.github.io/)
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

/**
 * NUBOMEDIA test.
 *
 * @author Boni Garcia (boni.garcia@urjc.es)
 * @since 6.5.1
 */
public class NubomediaBenchmarkTest extends BrowserTest<WebPage> {

  private final Logger log = LoggerFactory.getLogger(NubomediaBenchmarkTest.class);

  // Test parameters
  public static final String APP_URL_PROP = "app.url";
  public static final String APP_URL_DEFAULT = "https://localhost:8443/";
  public static final String SESSION_PLAYTIME_PROP = "session.play.time";
  public static final int SESSION_PLAYTIME_DEFAULT = 5;
  public static final String SESSION_RATE_PROP = "session.rate.time";
  public static final int SESSION_RATE_DEFAULT = 1000;

  // GUI parameters
  public static final String SESSIONS_NUMBER_PROP = "sessions.number";
  public static final int SESSIONS_NUMBER_DEFAULT = 1;
  public static final String POINTS_PER_SESSION_PROP = "points.per.session";
  public static final int POINTS_PER_SESSION_DEFAULT = 100;
  public static final String MEDIA_PROCESSING_PROP = "processing";
  public static final String MEDIA_PROCESSING_DEFAULT = "None";
  public static final String FAKE_CLIENTS_NUMBER_PROP = "fake.clients.number";
  public static final int FAKE_CLIENTS_NUMBER_DEFAULT = 0;
  public static final String FAKE_CLIENTS_RATE_PROP = "fake.clients.rate";
  public static final int FAKE_CLIENTS_RATE_DEFAULT = 1000;
  public static final String FAKE_CLIENTS_REMOVE_PROP = "fake.clients.remove";
  public static final boolean FAKE_CLIENTS_REMOVE_DEFAULT = false;
  public static final String FAKE_CLIENTS_TOGETHER_TIME_PROP = "fake.clients.play.time";
  public static final int FAKE_CLIENTS_TOGETHER_TIME_DEFAULT = 10;
  public static final String FAKE_CLIENTS_KMS_POINTS_PROP = "fake.clients.kms.points";
  public static final int FAKE_CLIENTS_KMS_POINTS_DEFAULT = 200;
  public static final String FAKE_CLIENTS_PER_KMS_PROP = "fake.clients.number.per.kms";
  public static final int FAKE_CLIENTS_PER_KMS_DEFAULT = 20;

  public int extraTimePerFakeClients = 0;

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
      }
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
    Map<String, Map<String, String>> presenterMap = getPresenter(index).getOcrMap();
    Map<String, Map<String, String>> viewerMap = getViewer(index).getOcrMap();

    // Stop recordings
    getPresenter(index).stopRecording();
    getViewer(index).stopRecording();

    // Store recordings
    File presenterFileRec = getPresenter(index).getRecording("presenter-session" + index + ".webm");
    File viewerFileRec = getViewer(index).getRecording("viewer-session" + index + ".webm");

    // Finish OCR
    getPresenter(index).endOcr();
    getViewer(index).endOcr();

    // Stop presenter and viewer(s)
    getPresenter(index).getBrowser().getWebDriver().findElement(By.id("stop")).click();
    getViewer(index).getBrowser().getWebDriver().findElement(By.id("stop")).click();

    // Close browsers
    getPresenter(index).close();
    getViewer(index).close();

    // Process data and write quality metrics
    log.info("[Session {}] Calulating quality of video", index);
    getQuality(presenterFileRec, viewerFileRec,
        this.getClass().getSimpleName() + "-session" + index + "-qov.csv");

    // Process data and write latency/statistics
    log.info("[Session {}] Calulating latency and collections statitics", index);
    processDataToCsv(this.getClass().getSimpleName() + "-session" + index + ".csv", presenterMap,
        viewerMap);

    log.info("[Session {}] End of test", index);
  }

}
