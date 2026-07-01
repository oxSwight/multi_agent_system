package com.midas.d3.quality;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the quality backstop (F4 follow-up). When {@link #enabled} (the default), a
 * Controller REJECT that has exhausted remediation is downgraded to {@code PASS_WITH_NOTES} instead of
 * routing to ERROR — but ONLY when {@link QualityBackstop} confirms substantive deterministic proof
 * (a non-empty gated rubric fully satisfied + the build did not fail). Set
 * {@code midas.quality.backstop.enabled=false} to keep the strict "Controller REJECT → ERROR" behavior.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.quality.backstop")
public class QualityBackstopProperties {

    private boolean enabled = true;
}
