package com.claimsflow.dashboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Enforces JWT authentication on the STOMP CONNECT frame (Week 4 hardening).
 *
 * <p>The HTTP handshake at {@code /ws} stays permitAll — browsers cannot set
 * an {@code Authorization} header on the WebSocket upgrade request, so the
 * standard pattern is to pass the bearer token as a STOMP native header on
 * CONNECT and validate it here. Reuses the same {@link JwtDecoder} as the
 * REST API — one token, both transports.
 *
 * <p>Frames after CONNECT ride the authenticated session; an unauthenticated
 * CONNECT is rejected before any subscription can be established.
 */
@Slf4j
@Component
public class JwtStompChannelInterceptor implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    public JwtStompChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;   // only CONNECT carries credentials
        }

        String token = bearerToken(accessor);
        if (token == null) {
            throw new BadCredentialsException("Missing Authorization header on STOMP CONNECT");
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);
            accessor.setUser(new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, List.of()));
            log.debug("WebSocket CONNECT authenticated sub={}", jwt.getSubject());
            return message;
        } catch (JwtException ex) {
            throw new BadCredentialsException("Invalid JWT on STOMP CONNECT: " + ex.getMessage());
        }
    }

    private String bearerToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).trim();
    }
}
