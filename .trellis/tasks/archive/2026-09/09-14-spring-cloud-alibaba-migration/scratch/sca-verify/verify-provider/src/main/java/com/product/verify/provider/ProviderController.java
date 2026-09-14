package com.product.verify.provider;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provider")
public class ProviderController {

    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "world") String name) {
        return "hello, " + name + " (from verify-provider)";
    }
}
