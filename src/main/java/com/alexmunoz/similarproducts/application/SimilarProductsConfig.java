package com.alexmunoz.similarproducts.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SimilarProductsConfig {

    @Bean
    NanoClock nanoClock() {
        return NanoClock.system();
    }

    /**
     * The cache owns its executor, one virtual thread per load, and Spring closes both on
     * shutdown. The executor is not a bean of its own: any {@code Executor} bean would make
     * Spring Boot back off its auto-configured task executor.
     */
    @Bean
    DetailLookupCache detailLookupCache(SimilarProductsProperties properties, NanoClock clock,
                                        MeterRegistry meterRegistry) {
        return new DetailLookupCache(properties.detailCache(), clock,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("detail-load-", 0).factory()),
                meterRegistry);
    }
}
