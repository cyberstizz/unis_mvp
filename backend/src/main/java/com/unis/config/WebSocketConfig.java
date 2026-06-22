package com.unis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket config for direct messaging.
 *
 * - Endpoint:        /ws (SockJS fallback enabled)
 * - Broker:          in-memory simple broker on /queue + /topic
 * - User prefix:     /user  (so clients subscribe to /user/queue/messages)
 * - App prefix:      /app   (reserved for future client->server STOMP sends;
 *                            v1 sends messages over REST, receives over STOMP)
 *
 * Scaling note: the in-memory simple broker is single-node. It comfortably
 * handles well past 10k DAU on one instance (concurrent sockets are a fraction
 * of DAU). When you horizontally scale on AWS, swap enableSimpleBroker(...) for
 * enableStompBrokerRelay(...) backed by Amazon MQ / RabbitMQ, or pin sessions
 * with ALB sticky sessions — neither touches application logic.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${app.frontend.url:http://localhost:5173}")
    private String frontendUrl;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Lock these down to your real origins in prod.
                .setAllowedOriginPatterns(frontendUrl, "http://localhost:*", "https://*.netlify.app")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}