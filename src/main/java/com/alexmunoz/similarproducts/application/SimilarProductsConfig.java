package com.alexmunoz.similarproducts.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SimilarProductsConfig {

    @Bean
    NanoClock nanoClock() {
        return NanoClock.system();
    }
}
