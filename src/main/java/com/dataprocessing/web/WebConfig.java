package com.dataprocessing.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccountIdInterceptor accountIdInterceptor;

    public WebConfig(AccountIdInterceptor accountIdInterceptor) {
        this.accountIdInterceptor = accountIdInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountIdInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**");
    }
}
