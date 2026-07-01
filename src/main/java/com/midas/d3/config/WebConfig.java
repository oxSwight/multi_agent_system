package com.midas.d3.config;

import com.midas.d3.ratelimit.RateLimitInterceptor;
import com.midas.d3.ratelimit.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration. Registers the {@link RateLimitInterceptor} across the whole REST surface
 * ({@code /api/**}) so both the pipeline and dashboard APIs are metered per caller.
 *
 * <p>The interceptor is resolved via {@link ObjectProvider} so this configuration also loads cleanly
 * in web-only slice tests ({@code @WebMvcTest}), which do not create the {@code @Component}
 * rate-limit beans — there the registration is simply skipped.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<RateLimitInterceptor> rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RateLimitInterceptor interceptor = rateLimitInterceptor.getIfAvailable();
        if (interceptor != null) {
            registry.addInterceptor(interceptor).addPathPatterns("/api/**");
        }
    }
}
