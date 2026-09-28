package com.example.carromgame.config;

import com.example.carromgame.game.CaromHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Tighten setAllowedOrigins for production.
        registry.addHandler(new CaromHandler(), "/ws/carom").setAllowedOrigins("*");
    }
}
