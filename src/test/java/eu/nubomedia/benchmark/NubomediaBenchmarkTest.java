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

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;
import org.kurento.test.base.BrowserTest;
import org.kurento.test.browser.Browser;
import org.kurento.test.browser.BrowserType;
import org.kurento.test.browser.WebPage;
import org.kurento.test.browser.WebPageType;
import org.kurento.test.config.BrowserConfig;
import org.kurento.test.config.BrowserScope;
import org.kurento.test.config.TestScenario;
import org.openqa.selenium.By;

/**
 * NUBOMEDIA test.
 *
 * @author Boni Garcia (boni.garcia@urjc.es)
 * @since 6.5.1
 */
public class NubomediaBenchmarkTest extends BrowserTest<WebPage> {

  private static final int PLAYTIME_SEC = 5;

  @Parameters(name = "{index}: {0}")
  public static Collection<Object[]> data() {
    TestScenario test = new TestScenario();
    test.addBrowser(BrowserConfig.PRESENTER,
        new Browser.Builder().webPageType(WebPageType.ROOT).browserType(BrowserType.CHROME)
            .scope(BrowserScope.LOCAL).url("https://localhost:8443/").build());
    test.addBrowser(BrowserConfig.VIEWER,
        new Browser.Builder().webPageType(WebPageType.ROOT).browserType(BrowserType.CHROME)
            .scope(BrowserScope.LOCAL).url("https://localhost:8443/").build());
    return Arrays.asList(new Object[][] { { test } });
  }

  @Test
  public void testNubomedia() throws Exception {
    // Start presenter
    getPresenter().getBrowser().getWebDriver().findElement(By.id("presenter")).click();
    // TODO enable
    // getPresenter().subscribeEvent("video", "playing");
    // getPresenter().waitForEvent("playing");

    // Start viewer
    getViewer().getBrowser().getWebDriver().findElement(By.id("viewer")).click();
    // TODO enable
    // getViewer().subscribeEvent("video", "playing");
    // getViewer().waitForEvent("playing");

    // Sync presenter and viewer time
    WebPage[] browsers = { getPresenter(), getViewer() };
    String[] videoTags = { "video", "video" };
    String[] peerConnections = { "webRtcPeer.peerConnection", "webRtcPeer.peerConnection" };
    syncTimeForOcr(browsers, videoTags, peerConnections);

    // Start OCR
    getPresenter().startOcr();
    getViewer().startOcr();

    // Play video
    waitSeconds(PLAYTIME_SEC);

    // Get OCR results and statistics
    Map<String, Map<String, String>> presenterMap = getPresenter().getOcrMap();
    Map<String, Map<String, String>> viewerMap = getViewer().getOcrMap();

    // Finish OCR, close browser, release media pipeline
    getPresenter().endOcr();
    getViewer().endOcr();
    // TODO enable
    // getPresenter().close();
    // getViewer().close();

    // Process data and write latency/statistics
    processDataToCsv(this.getClass().getSimpleName() + ".csv", presenterMap, viewerMap);
  }

}
