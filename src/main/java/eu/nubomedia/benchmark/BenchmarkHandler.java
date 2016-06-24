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

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.KurentoClient;
import org.kurento.client.MediaPipeline;
import org.kurento.client.OnIceCandidateEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.client.internal.NotEnoughResourcesException;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Handler (application and media logic).
 *
 * @author Boni Garcia (boni.garcia@urjc.es)
 * @since 6.5.1
 */
public class BenchmarkHandler extends TextWebSocketHandler {

  private final Logger log = LoggerFactory.getLogger(BenchmarkHandler.class);
  private final ConcurrentHashMap<String, UserSession> viewers = new ConcurrentHashMap<>();
  private KurentoClient kurentoClient;
  private MediaPipeline pipeline;
  private UserSession presenterUserSession;

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    JsonObject jsonMessage =
        new GsonBuilder().create().fromJson(message.getPayload(), JsonObject.class);
    log.debug("Incoming message from session '{}': {}", session.getId(), jsonMessage);

    try {
      switch (jsonMessage.get("id").getAsString()) {
        case "presenter":
          presenter(session, jsonMessage);
          break;
        case "viewer":
          viewer(session, jsonMessage);
          break;
        case "onIceCandidate": {
          JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();

          UserSession user = null;
          if (presenterUserSession != null) {
            if (presenterUserSession.getSession() == session) {
              user = presenterUserSession;
            } else {
              user = viewers.get(session.getId());
            }
          }
          if (user != null) {
            IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),
                candidate.get("sdpMid").getAsString(), candidate.get("sdpMLineIndex").getAsInt());
            user.addCandidate(cand);
          }
          break;
        }
        case "stop":
          stop(session);
          break;
        default:
          break;
      }

    } catch (NotEnoughResourcesException e) {
      log.warn("Not enough resources", e);
      notEnoughResources(session);

    } catch (Throwable t) {
      log.error("Exception starting session", t);
      handleErrorResponse(t, session, "error");
    }
  }

  private void handleErrorResponse(Throwable throwable, WebSocketSession session,
      String responseId) {
    stop(session);
    log.error(throwable.getMessage(), throwable);
    JsonObject response = new JsonObject();
    response.addProperty("id", responseId);
    response.addProperty("response", "rejected");
    response.addProperty("message", throwable.getMessage());
    sendMessage(session, new TextMessage(response.toString()));
  }

  private synchronized void presenter(final WebSocketSession session, JsonObject jsonMessage) {
    if (presenterUserSession == null) {
      presenterUserSession = new UserSession(session);

      // One KurentoClient instance per session
      kurentoClient = KurentoClient.create();

      pipeline = kurentoClient.createMediaPipeline();
      presenterUserSession.setWebRtcEndpoint(new WebRtcEndpoint.Builder(pipeline).build());

      WebRtcEndpoint presenterWebRtc = presenterUserSession.getWebRtcEndpoint();

      presenterWebRtc.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {

        @Override
        public void onEvent(OnIceCandidateEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          sendMessage(session, new TextMessage(response.toString()));
        }
      });

      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();
      String sdpAnswer = presenterWebRtc.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "presenterResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      sendMessage(presenterUserSession.getSession(), new TextMessage(response.toString()));
      presenterWebRtc.gatherCandidates();

    } else {
      JsonObject response = new JsonObject();
      response.addProperty("id", "presenterResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message",
          "Another user is currently acting as sender. Try again later ...");
      sendMessage(session, new TextMessage(response.toString()));
    }
  }

  private synchronized void viewer(final WebSocketSession session, JsonObject jsonMessage) {
    if (presenterUserSession == null || presenterUserSession.getWebRtcEndpoint() == null) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "viewerResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message",
          "No active sender now. Become sender or . Try again later ...");
      sendMessage(session, new TextMessage(response.toString()));
    } else {
      if (viewers.containsKey(session.getId())) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "viewerResponse");
        response.addProperty("response", "rejected");
        response.addProperty("message", "You are already viewing in this session. "
            + "Use a different browser to add additional viewers.");
        sendMessage(session, new TextMessage(response.toString()));
        return;
      }
      UserSession viewer = new UserSession(session);
      viewers.put(session.getId(), viewer);

      String fakeClients = jsonMessage.getAsJsonPrimitive("fakeClients").getAsString();
      String timeBetweenClients =
          jsonMessage.getAsJsonPrimitive("timeBetweenClients").getAsString();
      String playTime = jsonMessage.getAsJsonPrimitive("playTime").getAsString();
      String processing = jsonMessage.getAsJsonPrimitive("processing").getAsString();

      log.info("fakeClients {}, timeBetweenClients {}, playTime {}, processing {}", fakeClients,
          timeBetweenClients, playTime, processing);

      // TODO use these parameters

      WebRtcEndpoint nextWebRtc = new WebRtcEndpoint.Builder(pipeline).build();

      nextWebRtc.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {

        @Override
        public void onEvent(OnIceCandidateEvent event) {
          JsonObject response = new JsonObject();
          response.addProperty("id", "iceCandidate");
          response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
          sendMessage(session, new TextMessage(response.toString()));
        }
      });

      viewer.setWebRtcEndpoint(nextWebRtc);
      presenterUserSession.getWebRtcEndpoint().connect(nextWebRtc);
      String sdpOffer = jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString();

      String sdpAnswer = nextWebRtc.processOffer(sdpOffer);

      JsonObject response = new JsonObject();
      response.addProperty("id", "viewerResponse");
      response.addProperty("response", "accepted");
      response.addProperty("sdpAnswer", sdpAnswer);

      sendMessage(viewer.getSession(), new TextMessage(response.toString()));
      nextWebRtc.gatherCandidates();
    }
  }

  private synchronized void stop(WebSocketSession session) {
    String sessionId = session.getId();
    if (presenterUserSession != null
        && presenterUserSession.getSession().getId().equals(sessionId)) {
      for (UserSession viewer : viewers.values()) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "stopCommunication");
        sendMessage(viewer.getSession(), new TextMessage(response.toString()));
      }

      log.info("Releasing media pipeline");
      if (pipeline != null) {
        pipeline.release();
      }
      pipeline = null;
      presenterUserSession = null;

      log.info("Destroying kurentoClient (session {})", sessionId);
      kurentoClient.destroy();

    } else if (viewers.containsKey(sessionId)) {
      if (viewers.get(sessionId).getWebRtcEndpoint() != null) {
        viewers.get(sessionId).getWebRtcEndpoint().release();
      }
      viewers.remove(sessionId);
    }
  }

  private void notEnoughResources(WebSocketSession session) {
    // Send notEnoughResources message to client
    JsonObject response = new JsonObject();
    response.addProperty("id", "notEnoughResources");
    sendMessage(session, new TextMessage(response.toString()));

    // Release media session
    stop(session);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    stop(session);
  }

  public synchronized void sendMessage(WebSocketSession session, TextMessage message) {
    try {
      log.info("Sending message {} in session {}", message.getPayload(), session.getId());
      session.sendMessage(message);

    } catch (IOException e) {
      log.error("Exception sending message", e);
    }
  }

}
