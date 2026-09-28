package com.example.carromgame.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.example.carromgame.game.Phase.GAME_OVER;
import static com.example.carromgame.game.Phase.SHOOTING;
import static java.lang.Double.NaN;
import static java.util.Locale.ROOT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * One match. Every touch of the engine happens on {@link #executor}, so no locking is needed.
 */
public class GameRoom {
    private static final Logger log = LoggerFactory.getLogger(GameRoom.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final GameEngine engine = new GameEngine();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final WebSocketSession[] seats = new WebSocketSession[3]; // 1 = WHITE, 2 = BLACK

    public GameRoom(WebSocketSession white, WebSocketSession black) {
        seats[1] = white;
        seats[2] = black;
        send(1, "{\"t\":\"welcome\",\"color\":\"WHITE\"}");
        send(2, "{\"t\":\"welcome\",\"color\":\"BLACK\"}");
        broadcastState();
        executor.scheduleAtFixedRate(this::tick, 0, 16_666_667, NANOSECONDS);
    }

    public void handle(int seat, String text) {
        executor.execute(() -> {
            try {
                JsonNode m = objectMapper.readTree(text);
                switch (m.path("t").asString()) {
                    case "place" -> {
                        double x = engine.place(seat, m.path("x").asDouble(NaN));
                        if (!Double.isNaN(x)) broadcast("{\"t\":\"place\",\"x\":" + fmt(x) + "}");
                    }
                    case "aim" -> {
                        if (engine.canAct(seat)) send(3 - seat, text);   // opponent sees the guide line
                    }
                    case "shot" -> {
                        if (engine.shoot(seat, m.path("x").asDouble(NaN), m.path("dx").asDouble(NaN),
                                m.path("dy").asDouble(NaN), m.path("p").asDouble(NaN))) {
                            broadcast("{\"t\":\"aim\",\"clear\":true}");
                            broadcastState();
                        }
                    }
                    case "reset" -> {
                        if (engine.phase == GAME_OVER) {
                            engine.reset();
                            broadcastState();
                        }
                    }
                    default -> {
                    }
                }
            } catch (Exception e) {
                log.warn("Bad message from seat {}: {}", seat, e.toString());
            }
        });
    }

    private void tick() {
        try {
            if (engine.phase == SHOOTING) {
                engine.step();
                broadcast(snapshot());
                if (engine.atRest()) {
                    engine.resolveShot();
                    broadcastState();
                }
            } else if (engine.checkTimeout(System.nanoTime())) {
                broadcast("{\"t\":\"aim\",\"clear\":true}");
                broadcastState();
            }
        } catch (Throwable t) {
            log.error("Tick failed", t);   // never let an exception cancel the scheduled task
        }
    }

    public void close(int leavingSeat) {
        executor.shutdownNow();
        send(3 - leavingSeat, "{\"t\":\"left\"}");
    }

    // ---------------------------------------------------------------- outgoing
    private String snapshot() {
        StringBuilder sb = new StringBuilder("{\"t\":\"snap\",\"p\":[");
        boolean first = true;
        for (Piece p : engine.pieces) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(p.id).append(',').append(fmt(p.x)).append(',').append(fmt(p.y)).append(']');
        }
        return sb.append("]}").toString();
    }

    private void broadcastState() {
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("t", "state");
            s.put("turn", engine.turn);
            s.put("phase", engine.phase.name());
            s.put("msg", engine.message);
            s.put("winner", engine.winner);
            s.put("timeLeft", engine.millisLeft(System.nanoTime()));
            s.put("score", new int[]{engine.score(1), engine.score(2)});
            s.put("left", new int[]{Math.max(0, 9 - engine.pocketed[1]), Math.max(0, 9 - engine.pocketed[2])});
            s.put("queenOwner", engine.queenOwner);
            s.put("queenPending", engine.queenPending);
            List<Object[]> ps = new ArrayList<>();
            for (Piece p : engine.pieces) ps.add(new Object[]{p.id, p.kind.name(), round(p.x), round(p.y)});
            s.put("pieces", ps);
            broadcast(objectMapper.writeValueAsString(s));
        } catch (Exception e) {
            log.error("State serialisation failed", e);
        }
    }

    private void broadcast(String msg) {
        send(1, msg);
        send(2, msg);
    }

    private void send(int seat, String msg) {
        WebSocketSession s = seats[seat];
        try {
            if (s != null && s.isOpen()) s.sendMessage(new TextMessage(msg));
        } catch (Exception e) {
            log.debug("Send to seat {} failed: {}", seat, e.toString());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String fmt(double v) {
        return String.format(ROOT, "%.2f", v);
    }
}
