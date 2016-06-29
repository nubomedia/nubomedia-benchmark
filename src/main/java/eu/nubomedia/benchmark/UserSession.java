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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kurento.client.EventListener;
import org.kurento.client.FaceOverlayFilter;
import org.kurento.client.Filter;
import org.kurento.client.FilterType;
import org.kurento.client.GStreamerFilter;
import org.kurento.client.IceCandidate;
import org.kurento.client.ImageOverlayFilter;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaElement;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.Properties;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.client.ZBarFilter;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.module.chroma.ChromaFilter;
import org.kurento.module.chroma.WindowParam;
import org.kurento.module.crowddetector.CrowdDetectorFilter;
import org.kurento.module.crowddetector.RegionOfInterest;
import org.kurento.module.crowddetector.RegionOfInterestConfig;
import org.kurento.module.crowddetector.RelativePoint;
import org.kurento.module.platedetector.PlateDetectorFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.google.gson.JsonObject;

/**
 * User session.
 * 
 * @author Boni Garcia (boni.garcia@urjc.es)
 * @since 6.5.1
 */
public class UserSession {

  private final Logger log = LoggerFactory.getLogger(UserSession.class);

  private BenchmarkHandler handler;
  private WebSocketSession wsSession;
  private WebRtcEndpoint webRtcEndpoint;
  private KurentoClient kurentoClient;
  private MediaPipeline mediaPipeline;
  private String sessionNumber;
  private KurentoClient fakeKurentoClient;
  private MediaPipeline fakeMediaPipeline;
  private Filter filter;
  private List<MediaElement> mediaElementsInFakeMediaPipeline = new ArrayList<>();

  public UserSession(WebSocketSession wsSession, String sessionNumber, BenchmarkHandler handler) {
    this.wsSession = wsSession;
    this.sessionNumber = sessionNumber;
    this.handler = handler;
  }

  public void initPresenter(String sdpOffer, int loadPoints) {
    log.info("[Session number {} - WS session {}] Init presenter with {} points", sessionNumber,
        wsSession.getId(), loadPoints);

    Properties properties = new Properties();
    properties.add("loadPoints", loadPoints);
    kurentoClient = KurentoClient.create(properties);

    mediaPipeline = kurentoClient.createMediaPipeline();
    webRtcEndpoint = new WebRtcEndpoint.Builder(mediaPipeline).build();
    addOnIceCandidateListener();

    String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);
    JsonObject response = new JsonObject();
    response.addProperty("id", "presenterResponse");
    response.addProperty("response", "accepted");
    response.addProperty("sdpAnswer", sdpAnswer);

    handler.sendMessage(wsSession, sessionNumber, new TextMessage(response.toString()));
    webRtcEndpoint.gatherCandidates();
  }

  public void initViewer(UserSession presenterSession, String sdpOffer, String filterId,
      int fakePoints, int fakeClients, int timeBetweenClients, boolean removeFakeClients,
      int playTime) {

    log.info("[Session number {} - WS session {}] Init viewer(s) with {} filtering", sessionNumber,
        wsSession.getId(), filterId);

    mediaPipeline = presenterSession.getMediaPipeline();
    webRtcEndpoint = new WebRtcEndpoint.Builder(mediaPipeline).build();

    addOnIceCandidateListener();

    // Connectivity
    WebRtcEndpoint inputWebRtcEndpoint = presenterSession.getWebRtcEndpoint();
    filter = connectMediaElements(inputWebRtcEndpoint, filterId, webRtcEndpoint);

    String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);
    JsonObject response = new JsonObject();
    response.addProperty("id", "viewerResponse");
    response.addProperty("response", "accepted");
    response.addProperty("sdpAnswer", sdpAnswer);

    handler.sendMessage(wsSession, sessionNumber, new TextMessage(response.toString()));
    webRtcEndpoint.gatherCandidates();

    if (fakeClients > 0) {
      addFakeClients(presenterSession, fakePoints, fakeClients, timeBetweenClients, filterId,
          inputWebRtcEndpoint, removeFakeClients, playTime);
    }
  }

  private Filter connectMediaElements(MediaElement input, String filterId, MediaElement output) {
    Filter filter = null;
    switch (filterId) {
      case "Encoder":
        filter = new GStreamerFilter.Builder(mediaPipeline, "capsfilter caps=video/x-raw")
            .withFilterType(FilterType.VIDEO).build();
        break;
      case "FaceOverlayFilter":
        filter = new FaceOverlayFilter.Builder(mediaPipeline).build();
        break;
      case "ImageOverlayFilter":
        filter = new ImageOverlayFilter.Builder(mediaPipeline).build();
        break;
      case "ZBarFilter":
        filter = new ZBarFilter.Builder(mediaPipeline).build();
        break;
      case "PlateDetectorFilter":
        filter = new PlateDetectorFilter.Builder(mediaPipeline).build();
        break;
      case "CrowdDetectorFilter":
        List<RegionOfInterest> rois = getDummyRois();
        filter = new CrowdDetectorFilter.Builder(mediaPipeline, rois).build();
        break;
      case "ChromaFilter":
        filter = new ChromaFilter.Builder(mediaPipeline, new WindowParam(0, 0, 640, 480)).build();
        break;
      case "None":
      default:
        input.connect(output);
        log.info("[Session number {} - WS session {}] Pipeline: WebRtcEndpoint -> WebRtcEndpoint",
            sessionNumber, wsSession.getId());
        break;
    }

    if (filter != null) {
      input.connect(filter);
      filter.connect(output);
      int iFilter = filter.getName().lastIndexOf(".");
      String filterName = iFilter != -1 ? filter.getName().substring(iFilter + 1) : filterId;
      log.info(
          "[Session number {} - WS session {}] Pipeline: WebRtcEndpoint -> {} -> WebRtcEndpoint",
          sessionNumber, wsSession.getId(), filterName);
    }

    return filter;
  }

  private void addFakeClients(final UserSession presenterSession, final int fakePoints,
      final int fakeClients, final int timeBetweenClients, final String filterId,
      final WebRtcEndpoint inputWebRtcEndpoint, final boolean removeFakeClients,
      final int playTime) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        log.info("[Session number {} - WS session {}] Adding {} fake clients (rate {} ms) ",
            sessionNumber, wsSession.getId(), fakeClients, timeBetweenClients);

        if (fakeKurentoClient == null) {
          log.info(
              "[Session number {} - WS session {}] Creating a new kurentoClient for fake clients (reserving {} points)",
              sessionNumber, wsSession.getId(), fakePoints);
          Properties properties = new Properties();
          properties.add("loadPoints", fakePoints);
          fakeKurentoClient = KurentoClient.create(properties);
          fakeMediaPipeline = fakeKurentoClient.createMediaPipeline();

        } else {
          log.info(
              "[Session number {} - WS session {}] Reusing a kurentoClient for fake clients previously created",
              sessionNumber, wsSession.getId());
        }

        final CountDownLatch latch = new CountDownLatch(fakeClients);
        ExecutorService executor = Executors.newFixedThreadPool(fakeClients);
        for (int i = 0; i < fakeClients; i++) {
          waitMs(timeBetweenClients);
          executor.execute(new Runnable() {
            @Override
            public void run() {
              try {
                addFakeClient(filterId, inputWebRtcEndpoint);
              } finally {
                latch.countDown();
              }
            }
          });
        }

        try {
          latch.await();
        } catch (InterruptedException e) {
          log.warn("Exception waiting thread pool to be finished", e);
        }
        executor.shutdown();

        if (removeFakeClients) {
          log.info(
              "[Session number {} - WS session {}] Waiting {} seconds with all fake clients connected",
              sessionNumber, wsSession.getId(), playTime);
          waitMs(playTime * 1000);
          for (int i = 0; i < mediaElementsInFakeMediaPipeline.size() / 3; i++) {
            if (i != 0) {
              waitMs(timeBetweenClients);
            }
            log.info("[Session number {} - WS session {}] Releasing fake viewer {}", sessionNumber,
                wsSession.getId(), i);
            for (int j = 0; j < 3; j++) {
              MediaElement mediaElement = mediaElementsInFakeMediaPipeline.get(3 * i + j);
              if (mediaElement != null) {
                log.debug("[Session number {} - WS session {}] Releasing {}", sessionNumber,
                    wsSession.getId(), mediaElement);
                mediaElement.release();
                mediaElement = null;
              }
            }
          }
          mediaElementsInFakeMediaPipeline.clear();
          releaseFakeMediaPipeline();
        }
      }
    }).start();
  }

  private void waitMs(int waitTime) {
    try {
      log.debug("Waiting {} ms", waitTime);
      Thread.sleep(waitTime);
    } catch (InterruptedException e) {
      log.warn("Exception waiting {} ms", waitTime);
    }
  }

  private void addFakeClient(String filterId, WebRtcEndpoint inputWebRtc) {
    log.info("[Session number {} - WS session {}] Adding fake client with {} filtering",
        sessionNumber, wsSession.getId(), filterId);

    final WebRtcEndpoint fakeOutputWebRtc = new WebRtcEndpoint.Builder(mediaPipeline).build();
    final WebRtcEndpoint fakeBrowser = new WebRtcEndpoint.Builder(fakeMediaPipeline).build();

    Filter filter = connectMediaElements(inputWebRtc, filterId, fakeOutputWebRtc);

    fakeOutputWebRtc.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        fakeBrowser.addIceCandidate(event.getCandidate());
      }
    });

    fakeBrowser.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        fakeOutputWebRtc.addIceCandidate(event.getCandidate());
      }
    });

    String sdpOffer = fakeBrowser.generateOffer();
    String sdpAnswer = fakeOutputWebRtc.processOffer(sdpOffer);
    fakeBrowser.processAnswer(sdpAnswer);

    fakeOutputWebRtc.gatherCandidates();
    fakeBrowser.gatherCandidates();

    mediaElementsInFakeMediaPipeline.add(filter);
    mediaElementsInFakeMediaPipeline.add(fakeOutputWebRtc);
    mediaElementsInFakeMediaPipeline.add(fakeBrowser);
  }

  public void addCandidate(JsonObject jsonCandidate) {
    IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
        jsonCandidate.get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
    webRtcEndpoint.addIceCandidate(candidate);
  }

  private void addOnIceCandidateListener() {
    webRtcEndpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "iceCandidate");
        response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
        handler.sendMessage(wsSession, sessionNumber, new TextMessage(response.toString()));
      }
    });
  }

  public void releaseViewer() {
    log.info("[Session number {} - WS session {}] Releasing viewer", sessionNumber,
        wsSession.getId());

    if (filter != null) {
      log.debug("[Session number {} - WS session {}] Releasing filter", sessionNumber,
          wsSession.getId());
      filter.release();
      filter = null;
    }

    if (webRtcEndpoint != null) {
      log.debug("[Session number {} - WS session {}] Releasing WebRtcEndpoint", sessionNumber,
          wsSession.getId());
      webRtcEndpoint.release();
      webRtcEndpoint = null;
    }

    for (MediaElement mediaElement : mediaElementsInFakeMediaPipeline) {
      log.debug(
          "[Session number {} - WS session {}] Releasing media element {} in fake media pipeline",
          sessionNumber, wsSession.getId(), mediaElement);
      mediaElement.release();
      mediaElement = null;
    }
    mediaElementsInFakeMediaPipeline.clear();

    releaseFakeMediaPipeline();
  }

  private void releaseFakeMediaPipeline() {
    if (fakeMediaPipeline != null) {
      log.debug("[Session number {} - WS session {}] Releasing fake media pipeline", sessionNumber,
          wsSession.getId());
      fakeMediaPipeline.release();
      fakeMediaPipeline = null;
    }

    if (fakeKurentoClient != null) {
      log.debug("[Session number {} - WS session {}] Destroying fake kurentoClient", sessionNumber,
          wsSession.getId());
      fakeKurentoClient.destroy();
      fakeKurentoClient = null;
    }
  }

  public void releasePresenter() {
    log.info("[Session number {} - WS session {}] Releasing presenter", sessionNumber,
        wsSession.getId());

    if (mediaPipeline != null) {
      log.debug("[Session number {} - WS session {}] Releasing media pipeline", sessionNumber,
          wsSession.getId());
      mediaPipeline.release();
      mediaPipeline = null;
    }

    if (kurentoClient != null) {
      log.debug("[Session number {} - WS session {}] Destroying kurentoClient", sessionNumber,
          wsSession.getId());
      kurentoClient.destroy();
      kurentoClient = null;
    }
  }

  public WebSocketSession getWebSocketSession() {
    return wsSession;
  }

  public MediaPipeline getMediaPipeline() {
    return mediaPipeline;
  }

  public WebRtcEndpoint getWebRtcEndpoint() {
    return webRtcEndpoint;
  }

  public String getSessionNumber() {
    return sessionNumber;
  }

  private List<RegionOfInterest> getDummyRois() {
    List<RelativePoint> points = new ArrayList<>();

    float x = 0;
    float y = 0;
    points.add(new RelativePoint(x, y));

    x = 1;
    y = 0;
    points.add(new RelativePoint(x, y));

    x = 1;
    y = 1;
    points.add(new RelativePoint(x, y));

    x = 0;
    y = 1;
    points.add(new RelativePoint(x, y));

    RegionOfInterestConfig config = new RegionOfInterestConfig();
    config.setFluidityLevelMin(10);
    config.setFluidityLevelMed(35);
    config.setFluidityLevelMax(65);
    config.setFluidityNumFramesToEvent(5);
    config.setOccupancyLevelMin(10);
    config.setOccupancyLevelMed(35);
    config.setOccupancyLevelMax(65);
    config.setOccupancyNumFramesToEvent(5);
    config.setSendOpticalFlowEvent(false);
    config.setOpticalFlowNumFramesToEvent(3);
    config.setOpticalFlowNumFramesToReset(3);
    config.setOpticalFlowAngleOffset(0);

    List<RegionOfInterest> rois = new ArrayList<>();
    rois.add(new RegionOfInterest(points, config, "dummyRoy"));

    return rois;
  }

}
