package cpe.baldespompiers.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

// gère les clienst et OSRM route ?

@Configuration
public class RestClientConfig {

    @Bean(name = "simulatorWebClient")
    public WebClient simulatorWebClient(
            @Value("${simulator.base-url}") String baseUrl,
            @Value("${simulator.token}") String token) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }


    @Bean(name = "osrmWebClient")
    public WebClient osrmWebClient(@Value("${osrm.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}