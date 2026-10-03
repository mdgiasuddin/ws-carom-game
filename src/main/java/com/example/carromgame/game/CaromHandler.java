package com.example.carromgame.game;

import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ROOT;
import static org.springframework.web.socket.CloseStatus.BAD_DATA;
import static org.springframework.web.socket.CloseStatus.NORMAL;

/**
 * Pairs connections by room code instead of arrival order: the ?room=CODE query parameter on the
 * WebSocket handshake identifies the match, so two friends who use the same code find each other
 * regardless of who else is connecting to the server at the same time.
 */
public class CaromHandler extends TextWebSocketHandler {

    private static final int MAX_CODE_LEN = 20;

    private final Object lock = new Object();
    /**
     * code -> the first player waiting under that code.
     */
    private final Map<String, WebSocketSession> waitingByCode = new ConcurrentHashMap<>();
    /**
     * codes with a match already in progress, so a third connection can't join it.
     */
    private final Set<String> activeCodes = ConcurrentHashMap.newKeySet();
    private final Map<String, Seat> seatMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession raw) throws Exception {
        // sendMessage is not thread-safe; the decorator serialises sends from the ticker and handlers.
        // Long-lived: owned by the room and closed by the container, so no try-with-resources.
        @SuppressWarnings("resource")
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);

        String code = extractCode(raw.getUri());
        if (code == null) {
            session.sendMessage(new TextMessage("{\"t\":\"error\",\"msg\":\"Missing or invalid room code.\"}"));
            session.close(BAD_DATA);
            return;
        }
        // Stashed on the raw session (not the Seat) so afterConnectionClosed can free the code
        // whether this player ever got paired or was mid-game.
        raw.getAttributes().put("roomCode", code);

        synchronized (lock) {
            if (activeCodes.contains(code)) {
                session.sendMessage(new TextMessage(
                        "{\"t\":\"error\",\"msg\":\"That room code is already in use by two players.\"}"));
                session.close(NORMAL);
                return;
            }

            WebSocketSession white = waitingByCode.get(code);
            if (white == null) {
                waitingByCode.put(code, session);
                session.sendMessage(new TextMessage("{\"t\":\"wait\",\"code\":\"" + code + "\"}"));
                return;
            }

            waitingByCode.remove(code);
            activeCodes.add(code);
            GameRoom room = new GameRoom(white, session);
            seatMap.put(white.getId(), new Seat(room, 1));
            seatMap.put(session.getId(), new Seat(room, 2));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, @NonNull TextMessage message) {
        Seat seat = seatMap.get(raw.getId());
        if (seat != null) seat.room().handle(seat.seatId(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession raw, @NonNull CloseStatus status) {
        String code = (String) raw.getAttributes().get("roomCode");

        synchronized (lock) {
            WebSocketSession w = code == null ? null : waitingByCode.get(code);
            if (w != null && w.getId().equals(raw.getId())) {
                waitingByCode.remove(code);
            }
        }

        Seat seat = seatMap.remove(raw.getId());
        if (seat != null) {
            seat.room().close(seat.seatId());
            seatMap.values().removeIf(other -> other.room() == seat.room());
            if (code != null) activeCodes.remove(code);   // free the code so it can be reused later
        }
    }

    /**
     * Reads ?room=CODE from the handshake URL, keeping only A-Z0-9 and capping the length.
     */
    private static String extractCode(URI uri) {
        if (uri == null || uri.getQuery() == null) return null;
        for (String param : uri.getQuery().split("&")) {
            int eq = param.indexOf('=');
            if (eq < 0 || !"room".equals(param.substring(0, eq))) continue;
            String decoded = URLDecoder.decode(param.substring(eq + 1), UTF_8);
            String cleaned = decoded.toUpperCase(ROOT).replaceAll("[^A-Z0-9]", "");
            if (cleaned.isEmpty()) return null;
            return cleaned.length() > MAX_CODE_LEN ? cleaned.substring(0, MAX_CODE_LEN) : cleaned;
        }
        return null;
    }
}