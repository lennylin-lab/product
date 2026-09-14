package com.product.verify.core;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Feign contract resolved through Nacos discovery + Spring Cloud LoadBalancer.
 */
@FeignClient(name = "verify-provider", path = "/provider")
public interface ProviderClient {

    @GetMapping("/hello")
    String hello(@RequestParam("name") String name);
}
