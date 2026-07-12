package com.claimsflow.dashboard;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration for the real-time dashboard (FR-06).
 *
 * <p>Clients connect to {@code /ws} and subscribe to {@code /topic/metrics}.
 * The in-memory simple broker is sufficient for a single-instance modular
 * monolith; a multi-instance deployment would swap in a relay broker
 * (RabbitMQ/ActiveMQ STOMP) so all instances share subscriptions.
 *
 * <p>Auth (Week 4): the HTTP handshake is open, but the STOMP CONNECT frame
 * must carry a valid bearer token — see {@link JwtStompChannelInterceptor}.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtStompChannelInterceptor jwtInterceptor;

    public WebSocketConfig(JwtStompChannelInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtInterceptor);
    }
}
