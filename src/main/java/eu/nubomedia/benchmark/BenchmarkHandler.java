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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.internal.NotEnoughResourcesException;
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

  private Map<String, Map<String, UserSession>> viewers = new ConcurrentHashMap<>();
  private Map<String, UserSession> presenters = new ConcurrentHashMap<>();

  @Override
  public void handleTextMessage(WebSocketSession wsSession, TextMessage message) throws Exception {
    JsonObject jsonMessage =
        new GsonBuilder().create().fromJson(message.getPayload(), JsonObject.class);

    String sessionNumber = jsonMessage.get("sessionNumber").getAsString();
    log.info("Incoming message from session number {} and WS sessionId {} : {}", sessionNumber,
        wsSession.getId(), jsonMessage);

    try {
      switch (jsonMessage.get("id").getAsString()) {
        case "presenter":
          presenter(wsSession, sessionNumber,
              jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
          break;
        case "viewer":
          viewer(wsSession, sessionNumber,
              jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
          break;
        case "onIceCandidate":
          onIceCandidate(wsSession, sessionNumber, jsonMessage.get("candidate").getAsJsonObject());
          break;
        case "stop":
          stop(wsSession, sessionNumber);
        default:
          break;
      }

    } catch (NotEnoughResourcesException e) {
      log.warn("Not enough resources", e);
      notEnoughResources(wsSession, sessionNumber);

    } catch (Throwable t) {
      log.error("Exception starting session", t);
      handleErrorResponse(wsSession, sessionNumber, t);
    }
  }

  private synchronized void presenter(WebSocketSession wsSession, String sessionNumber,
      String sdpOffer) {
    if (presenters.containsKey(sessionNumber)) {
      JsonObject response = new JsonObject();
      response.addProperty("id", "presenterResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message", "Another user is currently acting as sender for session "
          + sessionNumber + ". Chose another session number ot try again later ...");
      sendMessage(wsSession, new TextMessage(response.toString()));

    } else {
      UserSession presenterSession = new UserSession(wsSession, this);
      presenters.put(sessionNumber, presenterSession);

      // TODO read points
      presenterSession.initPresenter(sdpOffer);
    }
  }

  private synchronized void viewer(WebSocketSession wsSession, String sessionNumber,
      String sdpOffer) {
    String wsSessionId = wsSession.getId();

    if (presenters.containsKey(sessionNumber)) {
      // Entry for viewers map
      Map<String, UserSession> viewersPerPresenter;
      if (viewers.containsKey(sessionNumber)) {
        viewersPerPresenter = viewers.get(sessionNumber);
      } else {
        viewersPerPresenter = new ConcurrentHashMap<>();
        viewers.put(sessionNumber, viewersPerPresenter);
      }

      if (viewersPerPresenter.containsKey(wsSessionId)) {
        JsonObject response = new JsonObject();
        response.addProperty("id", "viewerResponse");
        response.addProperty("response", "rejected");
        response.addProperty("message", "You are already viewing in session number "
            + viewersPerPresenter + ". Use a different browser/tab to add additional viewers.");
        sendMessage(wsSession, new TextMessage(response.toString()));

      } else {
        UserSession viewerSession = new UserSession(wsSession, this);
        viewersPerPresenter.put(wsSessionId, viewerSession);
        viewerSession.initViewer(presenters.get(sessionNumber), sdpOffer);
      }

    } else {
      JsonObject response = new JsonObject();
      response.addProperty("id", "viewerResponse");
      response.addProperty("response", "rejected");
      response.addProperty("message", "No active presenter for sesssion number " + sessionNumber
          + " now. Become sender or try again later ...");
      sendMessage(wsSession, new TextMessage(response.toString()));
    }
  }

  private synchronized void stop(WebSocketSession wsSession, String sessionNumber) {
    String wsSessionId = wsSession.getId();

    log.info("Stopping session number {} with WS sessionId {}", sessionNumber, wsSessionId);

    if (presenters.containsKey(sessionNumber)) {
      // 1. Stop arrive from presenter
      log.info("Releasing presenter of session number {} (WS sessionId {})", sessionNumber,
          wsSessionId);

      // Send stopCommunication to all viewers
      if (viewers.containsKey(sessionNumber)) {
        Map<String, UserSession> viewersPerPresenter = viewers.get(sessionNumber);
        log.info("There are {} viewers at this moment ({}) in session number {}",
            viewersPerPresenter.size(), viewersPerPresenter, sessionNumber);

        for (UserSession viewer : viewersPerPresenter.values()) {
          log.info(
              "Sending stopCommunication message to viewer (of session number {}) with WS sessionId {}",
              sessionNumber, viewer.getWebSocketSession().getId());

          JsonObject response = new JsonObject();
          response.addProperty("id", "stopCommunication");
          sendMessage(viewer.getWebSocketSession(), new TextMessage(response.toString()));
        }
        viewers.remove(sessionNumber);
      }

      // Release media pipeline and kurentoClient of presenter session
      presenters.get(sessionNumber).release();

    } else if (viewers.containsKey(sessionNumber)
        && viewers.get(sessionNumber).containsKey(wsSessionId)) {
      // 2. Stop arrive from presenter
      Map<String, UserSession> viewersPerPresenter = viewers.get(sessionNumber);
      log.info("There are {} viewers at this moment ({}) in session number {}",
          viewersPerPresenter.size(), viewersPerPresenter, sessionNumber);

      log.info("Releasing WebRtcEndpoing of viewer with WS sessionId {} in session number {}",
          wsSessionId, sessionNumber);
      viewersPerPresenter.get(wsSessionId).releaseWebRtcEndpoint();

      log.info("Removing viewer sessionId {}", wsSessionId);
      viewersPerPresenter.remove(wsSessionId);

      log.info("There are {} viewers at this moment ({}) in session number {}",
          viewersPerPresenter.size(), viewersPerPresenter, sessionNumber);
    }
  }

  private void onIceCandidate(WebSocketSession wsSession, String sessionNumber,
      JsonObject candidate) {
    UserSession user = null;
    String wsSessionId = wsSession.getId();

    // ICE candidate for presenter or viewer?
    if (presenters.containsKey(sessionNumber)) {
      user = presenters.get(sessionNumber);
    } else if (viewers.containsKey(sessionNumber)
        && viewers.get(sessionNumber).containsKey(wsSessionId)) {
      user = viewers.get(sessionNumber).get(wsSessionId);
    }
    if (user != null) {
      user.addCandidate(candidate);
    } else {
      log.warn(
          "*** ICE candidate not valid (WS sessionID {}) (session Number {}) (ICE candidate {}) ***",
          wsSessionId, sessionNumber, candidate);
    }
  }

  private void handleErrorResponse(WebSocketSession wsSession, String sessionNumber,
      Throwable throwable) {
    // Send error message to client
    JsonObject response = new JsonObject();
    response.addProperty("id", "error");
    response.addProperty("response", "rejected");
    response.addProperty("message", throwable.getMessage());
    sendMessage(wsSession, new TextMessage(response.toString()));
    log.error(throwable.getMessage(), throwable);

    // Release media session
    stop(wsSession, sessionNumber);
  }

  private void notEnoughResources(WebSocketSession wsSession, String sessionNumber) {
    // Send notEnoughResources message to client
    JsonObject response = new JsonObject();
    response.addProperty("id", "notEnoughResources");
    sendMessage(wsSession, new TextMessage(response.toString()));

    // Release media session
    stop(wsSession, sessionNumber);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession wsSession, CloseStatus status)
      throws Exception {
    String wsSessionId = wsSession.getId();
    log.info("Closed connection of WS sessionId {}", wsSessionId);

    // Find WS session in presenters
    for (String sessionNumber : presenters.keySet()) {
      if (presenters.get(sessionNumber).getWebSocketSession().getId().equals(wsSessionId)) {
        stop(wsSession, sessionNumber);
      }
    }

    // Find WS session in viewers
    for (String sessionNumber : viewers.keySet()) {
      for (UserSession userSession : viewers.get(sessionNumber).values()) {
        if (userSession.getWebSocketSession().getId().equals(wsSessionId)) {
          stop(wsSession, sessionNumber);
        }
      }
    }
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
