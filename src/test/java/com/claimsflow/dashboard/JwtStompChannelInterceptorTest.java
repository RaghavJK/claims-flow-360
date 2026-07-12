package com.claimsflow.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtStompChannelInterceptorTest {

    @Mock JwtDecoder jwtDecoder;
    MessageChannel channel = mock(MessageChannel.class);

    @InjectMocks JwtStompChannelInterceptor interceptor;

    @Test
    void validBearerTokenAuthenticatesConnect() {
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "HS256"), Map.of("sub", "adjuster@claimsflow.com"));
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);

        Message<?> result = interceptor.preSend(connectFrame("Bearer valid-token"), channel);

        assertThat(result).isNotNull();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("adjuster@claimsflow.com");
    }

    @Test
    void missingAuthorizationHeaderRejectsConnect() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame(null), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Missing Authorization");
    }

    @Test
    void invalidTokenRejectsConnect() {
        when(jwtDecoder.decode("garbage")).thenThrow(new JwtException("malformed"));

        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer garbage"), channel))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid JWT");
    }

    @Test
    void nonConnectFramesPassThroughUntouched() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/metrics");
        accessor.setLeaveMutable(true);
        Message<byte[]> subscribe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(subscribe, channel);

        assertThat(result).isSameAs(subscribe);   // no auth demanded post-CONNECT
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Message<byte[]> connectFrame(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
