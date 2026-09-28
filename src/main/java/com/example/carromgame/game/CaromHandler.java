package com.example.carromgame.game;

import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pairs connections in arrival order: first WHITE (bottom), second BLACK (top).
 */
public class CaromHandler extends TextWebSocketHandler {

    private final Object lock = new Object();
    private WebSocketSession waiting;
    private final Map<String, Seat> seats = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession raw) throws Exception {
        // sendMessage is not thread-safe; the decorator serialises sends from the ticker and handlers.
        // Long-lived: owned by the room and closed by the container, so no try-with-resources.
        @SuppressWarnings("resource")
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);

        synchronized (lock) {
            if (waiting == null) {
                waiting = session;
                session.sendMessage(new TextMessage("{\"t\":\"wait\"}"));
                return;
            }
            WebSocketSession white = waiting;
            waiting = null;
            GameRoom room = new GameRoom(white, session);
            seats.put(white.getId(), new Seat(room, 1));
            seats.put(session.getId(), new Seat(room, 2));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, @NonNull TextMessage message) {
        Seat s = seats.get(raw.getId());
        if (s != null) s.room().handle(s.seat(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession raw, @NonNull CloseStatus status) {
        synchronized (lock) {
            if (waiting != null && waiting.getId().equals(raw.getId())) waiting = null;
        }
        Seat s = seats.remove(raw.getId());
        if (s != null) {
            s.room().close(s.seat());
            for (var e : seats.entrySet())
                if (e.getValue().room() == s.room()) seats.remove(e.getKey());
        }
    }
}
