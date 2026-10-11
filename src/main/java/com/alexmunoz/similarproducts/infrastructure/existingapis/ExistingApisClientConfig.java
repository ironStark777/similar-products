package com.alexmunoz.similarproducts.infrastructure.existingapis;

import java.net.http.HttpClient;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * HTTP client for the existing APIs. Base URL and timeouts are configured through the
 * {@code spring.http.serviceclient.products.*} properties.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "products", types = ExistingProductsApi.class)
class ExistingApisClientConfig {

    /**
     * JDK HttpClient forced to HTTP/1.1: by default it tries an HTTP/2 (h2c) upgrade on
     * plain http connections, which the existing APIs do not need.
     */
    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(builder -> builder.version(HttpClient.Version.HTTP_1_1));
    }
}
