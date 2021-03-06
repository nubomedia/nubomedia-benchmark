/*
 * (C) Copyright 2016 NUBOMEDIA (http://www.nubomedia.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package eu.nubomedia.benchmark;

import static org.kurento.commons.PropertiesManager.getProperty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.kurento.client.ElementConnectionData;
import org.kurento.client.ElementStats;
import org.kurento.client.EndpointStats;
import org.kurento.client.EventListener;
import org.kurento.client.FaceOverlayFilter;
import org.kurento.client.FilterType;
import org.kurento.client.GStreamerFilter;
import org.kurento.client.IceCandidate;
import org.kurento.client.ImageOverlayFilter;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaElement;
import org.kurento.client.MediaLatencyStat;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaType;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.PassThrough;
import org.kurento.client.Properties;
import org.kurento.client.RecorderEndpoint;
import org.kurento.client.Stats;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.client.ZBarFilter;
import org.kurento.jsonrpc.JsonUtils;
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

  private final static String FAKE_KMS_WS_URI_PROP = "fake.kms.ws.uri";
  private final static String KMS_WS_URI_PROP = "kms.ws.uri";
  private final static String FAKE_KMS_SEPARATOR_CHAR = ",";

  private BenchmarkHandler handler;
  private WebSocketSession wsSession;
  private WebRtcEndpoint webRtcEndpoint;
  private KurentoClient kurentoClient;
  private MediaElement filter;
  private MediaPipeline mediaPipeline;
  private String sessionNumber;
  private List<KurentoClient> fakeKurentoClients = new ArrayList<>();
  private List<MediaPipeline> fakeMediaPipelines = new ArrayList<>();
  private List<KurentoClient> extraKurentoClients = new ArrayList<>();
  private List<MediaPipeline> extraMediaPipelines = new ArrayList<>();
  private Map<String, List<MediaElement>> mediaElementsInFakeMediaPipelineMap =
      new ConcurrentSkipListMap<>();
  private List<MediaElement> mediaElementsInExtraMediaPipelineList = new ArrayList<>();
  private Queue<String> fakeKmsUriQueue;
  private List<Double> mediaPipelineLatencies = new ArrayList<>();
  private List<Double> filterLatencies = new ArrayList<>();
  private Thread latencyThread;
  private int bandwidth;

  public UserSession(WebSocketSession wsSession, String sessionNumber, BenchmarkHandler handler,
      int bandwidth) {
    this.wsSession = wsSession;
    this.sessionNumber = sessionNumber;
    this.handler = handler;
    this.bandwidth = bandwidth;
  }

  public void initPresenter(String sdpOffer, int loadPoints) {
    log.info("[Session number {} - WS session {}] Init presenter", sessionNumber,
        wsSession.getId());

    kurentoClient = createKurentoClient(loadPoints);
    mediaPipeline = kurentoClient.createMediaPipeline();
    webRtcEndpoint = createWebRtcEndpoint(mediaPipeline);

    addOnIceCandidateListener();

    String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);
    JsonObject response = new JsonObject();
    response.addProperty("id", "presenterResponse");
    response.addProperty("response", "accepted");
    response.addProperty("sdpAnswer", sdpAnswer);

    handler.sendMessage(wsSession, sessionNumber, new TextMessage(response.toString()));
    webRtcEndpoint.gatherCandidates();

  }

  private KurentoClient createKurentoClient(int loadPoints) {
    KurentoClient kurentoClient;
    String wsUri = getProperty(KMS_WS_URI_PROP);
    if (wsUri != null) {
      log.info("[Session number {} - WS session {}] Using KMS URI {} to create KurentoClient",
          sessionNumber, wsSession.getId(), wsUri);
      kurentoClient = KurentoClient.create(wsUri);
    } else {
      log.info("[Session number {} - WS session {}] Reserving {} points to create KurentoClient",
          sessionNumber, wsSession.getId(), loadPoints);
      Properties properties = new Properties();
      properties.add("loadPoints", loadPoints);
      kurentoClient = KurentoClient.create(properties);
    }
    return kurentoClient;
  }

  private Thread gatherLatencies(final int rateKmsLatency) {
    webRtcEndpoint.getMediaPipeline().setLatencyStats(true);

    Thread thread = new Thread(new Runnable() {
      @Override
      public void run() {
        while (true) {
          try {
            if (webRtcEndpoint != null) {
              Map<String, Stats> stats = webRtcEndpoint.getStats(MediaType.VIDEO);
              Collection<Stats> values = stats.values();
              for (Stats s : values) {
                if (s instanceof EndpointStats) {
                  List<MediaLatencyStat> e2eLatency = ((EndpointStats) s).getE2ELatency();
                  if (!e2eLatency.isEmpty()) {
                    mediaPipelineLatencies.add(e2eLatency.get(0).getAvg() / 1000); // microseconds
                  }
                }
              }
            }

            // Filter latency
            if (filter != null) {
              double l1 = 0;
              Map<String, Stats> filterStats = filter.getStats(MediaType.VIDEO);
              for (Stats s : filterStats.values()) {
                if (s instanceof ElementStats) {
                  List<MediaLatencyStat> inputLatency = ((ElementStats) s).getInputLatency();
                  if (!inputLatency.isEmpty()) {
                    l1 = inputLatency.get(0).getAvg();
                  }
                }
              }
              double l2 = 0;
              if (webRtcEndpoint != null) {
                Map<String, Stats> webRtcEndpointStats = webRtcEndpoint.getStats(MediaType.VIDEO);
                for (Stats s : webRtcEndpointStats.values()) {
                  if (s instanceof ElementStats) {
                    List<MediaLatencyStat> inputLatency = ((ElementStats) s).getInputLatency();
                    if (!inputLatency.isEmpty()) {
                      l2 = inputLatency.get(0).getAvg();
                    }
                  }
                }
              }
              filterLatencies.add((l2 - l1) / 1000); // microseconds
            }

          } catch (Exception e) {
            log.debug("Exception gathering videoE2ELatency {}", e.getMessage());
            return;
          } finally {
            try {
              Thread.sleep(rateKmsLatency);
            } catch (InterruptedException e) {
              log.debug("Interrupted thread for gathering videoE2ELatency");
              return;
            }
          }
        }
      }
    });
    thread.start();
    return thread;

  }

  public void initViewer(UserSession presenterSession, JsonObject jsonMessage)
      throws InterruptedException {
    String processing = jsonMessage.get("processing").getAsString();
    String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
    int fakeClients = jsonMessage.getAsJsonPrimitive("fakeClients").getAsInt();
    int rateKmsLatency = jsonMessage.getAsJsonPrimitive("rateKmsLatency").getAsInt();
    String kmsTopology = jsonMessage.get("kmsTopology").getAsString();
    int kmsNumber = jsonMessage.getAsJsonPrimitive("kmsNumber").getAsInt();
    int webrtcChannels = jsonMessage.getAsJsonPrimitive("webrtcChannels").getAsInt();
    int loadPoints = jsonMessage.getAsJsonPrimitive("loadPoints").getAsInt();
    int kmsRate = jsonMessage.getAsJsonPrimitive("kmsRate").getAsInt();
    boolean kmsLatency = jsonMessage.getAsJsonPrimitive("kmsLatency").getAsBoolean();

    WebRtcEndpoint inputWebRtcEndpoint = (kmsTopology.equalsIgnoreCase("tree"))
        ? treeKmsTopology(presenterSession, processing, kmsNumber, webrtcChannels, loadPoints,
            kmsRate)
        : singleKmsTopology(presenterSession, processing);

    String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);
    JsonObject response = new JsonObject();
    response.addProperty("id", "viewerResponse");
    response.addProperty("response", "accepted");
    response.addProperty("sdpAnswer", sdpAnswer);

    handler.sendMessage(wsSession, sessionNumber, new TextMessage(response.toString()));
    webRtcEndpoint.gatherCandidates();

    if (kmsTopology.equalsIgnoreCase("single") && fakeClients > 0) {
      addFakeClients(presenterSession, jsonMessage, inputWebRtcEndpoint);
    }

    log.info("[Session number {} - WS session {}] Gather KMS latency: {}", sessionNumber,
        wsSession.getId(), kmsLatency);
    if (kmsLatency) {
      // Viewer videoE2ELatency
      latencyThread = gatherLatencies(rateKmsLatency);
    }
  }

  private WebRtcEndpoint treeKmsTopology(UserSession presenterSession, final String processing,
      final int kmsNumber, final int webrtcChannels, final int loadPoints, final int kmsRate)
      throws InterruptedException {
    log.info(
        "[Session number {} - WS session {}] Init viewer(s) with {} filtering"
            + " (Tree with {} KMS and {} WebRTC channels)",
        sessionNumber, wsSession.getId(), processing, kmsNumber, webrtcChannels);

    // Connectivity
    WebRtcEndpoint inputWebRtcEndpoint = presenterSession.getWebRtcEndpoint();
    mediaPipeline = presenterSession.getMediaPipeline();

    for (int i = 0; i < webrtcChannels * kmsNumber; i++) {
      WebRtcEndpoint sourceWebRtc = createWebRtcEndpoint(mediaPipeline);
      mediaElementsInExtraMediaPipelineList.add(sourceWebRtc);
      connectMediaElements(inputWebRtcEndpoint, processing, sourceWebRtc);
    }

    final CountDownLatch latch = new CountDownLatch(1);
    new Thread(new Runnable() {
      @Override
      public void run() {
        // Rest of KMS(s)
        int webRtcIndex = 0;
        for (int j = 0; j < kmsNumber; j++) {
          KurentoClient extraKurentoClient = createKurentoClient(loadPoints);
          MediaPipeline extraMediaPipeline = extraKurentoClient.createMediaPipeline();

          extraMediaPipelines.add(extraMediaPipeline);
          extraKurentoClients.add(extraKurentoClient);

          long startTime = System.currentTimeMillis();

          for (int i = 0; i < webrtcChannels; i++) {
            WebRtcEndpoint targetWebRtc = createWebRtcEndpoint(extraMediaPipeline);
            connectWebRtcEndpoints(
                (WebRtcEndpoint) mediaElementsInExtraMediaPipelineList.get(webRtcIndex),
                targetWebRtc);
            webRtcIndex++;

            MediaElement finalMediaElement;
            if (j == 0 && i == 0) {
              finalMediaElement = createWebRtcEndpoint(extraMediaPipeline);
              webRtcEndpoint = (WebRtcEndpoint) finalMediaElement;
              addOnIceCandidateListener();
              latch.countDown();
            } else {
              finalMediaElement =
                  new RecorderEndpoint.Builder(extraMediaPipeline, "file:///dev/null").build();
              ((RecorderEndpoint) finalMediaElement).record();
            }

            MediaElement connectMediaElements =
                connectMediaElements(targetWebRtc, processing, finalMediaElement);
            if (j == 0 && i == 0) {
              filter = connectMediaElements;
            }
          }

          // Wait among KMSs
          try {
            long channelsTime = System.currentTimeMillis() - startTime;
            long waitTime = TimeUnit.SECONDS.toMillis(kmsRate) - channelsTime;
            if (waitTime > 0) {
              log.info(
                  "[Session number {} - WS session {}] Waiting {} seconds"
                      + " (actual wait {} ms, since the WebRTC channels connections last {} ms)",
                  sessionNumber, wsSession.getId(), kmsRate, waitTime, channelsTime);
              Thread.sleep(waitTime);
            } else {
              log.info(
                  "[Session number {} - WS session {}] Waiting {} seconds"
                      + " (already waited while connecting WebRTC channels in {} ms)",
                  sessionNumber, wsSession.getId(), kmsRate, channelsTime);
            }
          } catch (InterruptedException e) {
            log.warn("Interrupted thread while polling");
          }
        }
      }
    }).start();

    latch.await();

    return webRtcEndpoint;
  }

  private void connectWebRtcEndpoints(final WebRtcEndpoint webRtcEndpoint1,
      final WebRtcEndpoint webRtcEndpoint2) {
    webRtcEndpoint1.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        webRtcEndpoint2.addIceCandidate(event.getCandidate());
      }
    });

    webRtcEndpoint2.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        webRtcEndpoint1.addIceCandidate(event.getCandidate());
      }
    });

    String sdpOffer = webRtcEndpoint2.generateOffer();
    String sdpAnswer = webRtcEndpoint1.processOffer(sdpOffer);
    webRtcEndpoint2.processAnswer(sdpAnswer);

    webRtcEndpoint1.gatherCandidates();
    webRtcEndpoint2.gatherCandidates();
  }

  private WebRtcEndpoint singleKmsTopology(UserSession presenterSession, String processing) {
    log.info("[Session number {} - WS session {}] Init viewer(s) with {} filtering {} (Single KMS)",
        sessionNumber, wsSession.getId(), processing);

    mediaPipeline = presenterSession.getMediaPipeline();
    webRtcEndpoint = createWebRtcEndpoint(mediaPipeline);

    addOnIceCandidateListener();

    // Connectivity
    WebRtcEndpoint inputWebRtcEndpoint = presenterSession.getWebRtcEndpoint();
    filter = connectMediaElements(inputWebRtcEndpoint, processing, webRtcEndpoint);

    return inputWebRtcEndpoint;
  }

  private MediaElement connectMediaElements(MediaElement input, String filterId,
      MediaElement output) {
    MediaPipeline mediaPipeline = input.getMediaPipeline();
    MediaElement filter = null;
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
      case "PassThrough":
        filter = new PassThrough.Builder(mediaPipeline).build();
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

  private void addFakeClients(UserSession presenterSession, JsonObject jsonMessage,
      final WebRtcEndpoint inputWebRtcEndpoint) {

    final String sessionNumber = jsonMessage.get("sessionNumber").getAsString();
    final int fakeClients = jsonMessage.getAsJsonPrimitive("fakeClients").getAsInt();
    final int timeBetweenClients = jsonMessage.getAsJsonPrimitive("timeBetweenClients").getAsInt();
    final boolean removeFakeClients =
        jsonMessage.getAsJsonPrimitive("removeFakeClients").getAsBoolean();
    final int playTime = jsonMessage.getAsJsonPrimitive("playTime").getAsInt();
    final String processing = jsonMessage.get("processing").getAsString();
    final int fakePoints = jsonMessage.getAsJsonPrimitive("fakePoints").getAsInt();
    final int fakeClientsPerInstance =
        jsonMessage.getAsJsonPrimitive("fakeClientsPerInstance").getAsInt();

    new Thread(new Runnable() {
      @Override
      public void run() {
        log.info("[Session number {} - WS session {}] Adding {} fake clients (rate {} ms) ",
            sessionNumber, wsSession.getId(), fakeClients, timeBetweenClients);

        final CountDownLatch latch = new CountDownLatch(fakeClients);
        ExecutorService executor = Executors.newFixedThreadPool(fakeClients);
        for (int i = 0; i < fakeClients; i++) {
          waitMs(timeBetweenClients);
          final int j = i + 1;
          executor.execute(new Runnable() {
            @Override
            public void run() {
              try {
                addFakeClient(j, processing, inputWebRtcEndpoint, fakePoints,
                    fakeClientsPerInstance);
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

          for (List<MediaElement> list : mediaElementsInFakeMediaPipelineMap.values()) {
            waitMs(playTime * 1000);
            for (int i = 0; i < list.size() / 3; i++) {
              if (i != 0) {
                waitMs(timeBetweenClients);
              }
              log.info("[Session number {} - WS session {}] Releasing fake viewer {}",
                  sessionNumber, wsSession.getId(), i);
              for (int j = 0; j < 3; j++) {
                MediaElement mediaElement = list.get(3 * i + j);
                if (mediaElement != null) {
                  log.debug("[Session number {} - WS session {}] Releasing {}", sessionNumber,
                      wsSession.getId(), mediaElement);
                  mediaElement.release();
                  mediaElement = null;
                }
              }
            }
          }
          mediaElementsInFakeMediaPipelineMap.clear();
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

  private void addFakeClient(int count, String filterId, WebRtcEndpoint inputWebRtc, int fakePoints,
      int fakeClientsPerInstance) {
    log.info("[Session number {} - WS session {}] Adding fake client #{} with {} filtering",
        sessionNumber, wsSession.getId(), count, filterId);

    if (fakeKurentoClients.isEmpty()) {
      createNewFakeKurentoClient(fakePoints);
    }
    KurentoClient fakeKurentoClient = fakeKurentoClients.get(fakeKurentoClients.size() - 1);
    MediaPipeline fakeMediaPipeline = fakeMediaPipelines.get(fakeMediaPipelines.size() - 1);

    int fakeClientNumber = 0;
    String fakeMediaPipelineId = fakeMediaPipeline.getId();
    List<MediaElement> currentMediaElementList;

    log.debug("[Session number {} - WS session {}] mediaElementsInFakeMediaPipelineMap {}",
        sessionNumber, wsSession.getId(), mediaElementsInFakeMediaPipelineMap.keySet());
    if (mediaElementsInFakeMediaPipelineMap.containsKey(fakeMediaPipelineId)) {
      currentMediaElementList = mediaElementsInFakeMediaPipelineMap.get(fakeMediaPipelineId);
      fakeClientNumber = currentMediaElementList.size() / 3;
      log.debug(
          "[Session number {} - WS session {}] Number of existing fake clients: {} (Media Pipeline {})",
          sessionNumber, wsSession.getId(), fakeClientNumber, fakeMediaPipelineId);

    } else {
      currentMediaElementList = new ArrayList<>();
      mediaElementsInFakeMediaPipelineMap.put(fakeMediaPipelineId, currentMediaElementList);
      log.debug("[Session number {} - WS session {}] There is no existing fake clients so far",
          sessionNumber, wsSession.getId());
    }

    if (fakeClientNumber >= fakeClientsPerInstance) {
      log.info(
          "[Session number {} - WS session {}] The number of current fake clients is {}, which is "
              + " greater or equal that {} and so a new KurentoClient for fake clients is created",
          sessionNumber, wsSession.getId(), fakeClientNumber, fakeClientsPerInstance);
      createNewFakeKurentoClient(fakePoints);
      fakeKurentoClient = fakeKurentoClients.get(fakeKurentoClients.size() - 1);
      fakeMediaPipeline = fakeMediaPipelines.get(fakeMediaPipelines.size() - 1);

      fakeMediaPipelineId = fakeMediaPipeline.getId();
      currentMediaElementList = new ArrayList<>();
      mediaElementsInFakeMediaPipelineMap.put(fakeMediaPipelineId, currentMediaElementList);
    }

    if (!fakeKurentoClients.contains(fakeKurentoClient)) {
      fakeKurentoClients.add(fakeKurentoClient);
    }

    final WebRtcEndpoint fakeOutputWebRtc = createWebRtcEndpoint(mediaPipeline);
    final WebRtcEndpoint fakeBrowser = createWebRtcEndpoint(fakeMediaPipeline);

    MediaElement filter = connectMediaElements(inputWebRtc, filterId, fakeOutputWebRtc);

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

    currentMediaElementList.add(filter);
    currentMediaElementList.add(fakeOutputWebRtc);
    currentMediaElementList.add(fakeBrowser);

  }

  private void createNewFakeKurentoClient(int fakePoints) {
    String fakeKmsUriProp = getProperty(FAKE_KMS_WS_URI_PROP);
    KurentoClient fakeKurentoClient;
    if (fakeKmsUriProp != null) {
      String fakeKmsUri = getNextFakeKmsUri(fakeKmsUriProp);
      log.info(
          "[Session number {} - WS session {}] Using KMS URI {} to create creating a new kurentoClient for fake clients",
          sessionNumber, wsSession.getId(), fakeKmsUri);
      fakeKurentoClient = KurentoClient.create(fakeKmsUri);
    } else {
      log.info(
          "[Session number {} - WS session {}] Creating a new kurentoClient for fake clients (reserving {} points)",
          sessionNumber, wsSession.getId(), fakePoints);

      Properties properties = new Properties();
      properties.add("loadPoints", fakePoints);
      fakeKurentoClient = KurentoClient.create(properties);

      log.info("[Session number {} - WS session {}] New KurentoClient for fake clients {}",
          sessionNumber, wsSession.getId(), fakeKurentoClient);
    }

    MediaPipeline fakeMediaPipeline = fakeKurentoClient.createMediaPipeline();

    fakeKurentoClients.add(fakeKurentoClient);
    fakeMediaPipelines.add(fakeMediaPipeline);

    log.debug(
        "[Session number {} - WS session {}] Created Media Pipeline for fake clients with id {}",
        sessionNumber, wsSession.getId(), fakeMediaPipeline.getId());
  }

  private String getNextFakeKmsUri(String fakeKmsUriProp) {
    String nextUri = null;
    if (fakeKmsUriQueue == null) {
      fakeKmsUriQueue = new CircularFifoQueue<String>();
      if (fakeKmsUriProp.contains(FAKE_KMS_SEPARATOR_CHAR)) {
        String[] split = fakeKmsUriProp.split(FAKE_KMS_SEPARATOR_CHAR);
        for (String s : split) {
          fakeKmsUriQueue.add(s);
        }
      } else {
        fakeKmsUriQueue.add(fakeKmsUriProp);
      }
    }
    nextUri = fakeKmsUriQueue.poll();
    fakeKmsUriQueue.add(nextUri);
    return nextUri;
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

    if (latencyThread != null) {
      log.debug("[Session number {} - WS session {}] Releasing latencies thread", sessionNumber,
          wsSession.getId());
      latencyThread.interrupt();
    }

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

    for (MediaElement me : mediaElementsInExtraMediaPipelineList) {
      for (ElementConnectionData e : me.getSourceConnections()) {
        MediaElement source = e.getSource();
        if (!(source instanceof WebRtcEndpoint)) {
          source.release();
        }
      }
      if (me != null) {
        me.release();
      }
    }
    mediaElementsInExtraMediaPipelineList.clear();

    for (List<MediaElement> list : mediaElementsInFakeMediaPipelineMap.values()) {
      for (MediaElement mediaElement : list) {
        if (mediaElement != null) {
          log.debug(
              "[Session number {} - WS session {}] Releasing media element {} in fake media pipeline",
              sessionNumber, wsSession.getId(), mediaElement);
          mediaElement.release();
          mediaElement = null;
        }
      }
      list.clear();
    }
    mediaElementsInFakeMediaPipelineMap.clear();

    releaseFakeMediaPipeline();

  }

  private void releaseFakeMediaPipeline() {
    if (!fakeMediaPipelines.isEmpty()) {
      log.debug("[Session number {} - WS session {}] Releasing fake media pipeline", sessionNumber,
          wsSession.getId());
      for (MediaPipeline mp : fakeMediaPipelines) {
        mp.release();
        mp = null;
      }
      fakeMediaPipelines.clear();
    }

    if (!fakeKurentoClients.isEmpty()) {
      log.debug("[Session number {} - WS session {}] Destroying fake kurentoClient", sessionNumber,
          wsSession.getId());
      for (KurentoClient kc : fakeKurentoClients) {
        kc.destroy();
        kc = null;
      }
      fakeKurentoClients.clear();
    }

    if (!extraMediaPipelines.isEmpty()) {
      log.debug("[Session number {} - WS session {}] Releasing extra media pipeline", sessionNumber,
          wsSession.getId());
      for (MediaPipeline mp : extraMediaPipelines) {
        mp.release();
        mp = null;
      }
      extraMediaPipelines.clear();
    }

    if (!extraKurentoClients.isEmpty()) {
      log.debug("[Session number {} - WS session {}] Destroying extra kurentoClient", sessionNumber,
          wsSession.getId());
      for (KurentoClient kc : extraKurentoClients) {
        kc.destroy();
        kc = null;
      }
      extraKurentoClients.clear();
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

  private WebRtcEndpoint createWebRtcEndpoint(MediaPipeline mediaPipeline) {
    WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(mediaPipeline).build();
    webRtcEndpoint.setMaxVideoSendBandwidth(bandwidth);
    webRtcEndpoint.setMinVideoSendBandwidth(bandwidth);
    webRtcEndpoint.setMaxVideoRecvBandwidth(bandwidth);
    webRtcEndpoint.setMinVideoRecvBandwidth(bandwidth);
    return webRtcEndpoint;
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

  public List<Double> getMediaPipelineLatencies() {
    return mediaPipelineLatencies;
  }

  public List<Double> getFilterLatencies() {
    return filterLatencies;
  }

}
