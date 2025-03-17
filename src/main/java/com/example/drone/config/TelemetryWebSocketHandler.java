package com.example.drone.config;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class TelemetryWebSocketHandler extends TextWebSocketHandler {
    private static final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Not needed since we're only sending data, not receiving commands
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
        sessions.remove(session);
    }

    public static void sendTelemetryData(Map<String, Object> telemetryData) {
        try {
            String jsonData = objectMapper.writeValueAsString(telemetryData);
            for (WebSocketSession session : sessions) {
                session.sendMessage(new TextMessage(jsonData));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendMissionData(List<Map<String, Object>> missionDataList) {
        try {
            String jsonData = objectMapper.writeValueAsString(missionDataList);
            for (WebSocketSession session : sessions) {
                session.sendMessage(new TextMessage(jsonData));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}