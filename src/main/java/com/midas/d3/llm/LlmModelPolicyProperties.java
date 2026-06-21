package com.midas.d3.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.llm")
public class LlmModelPolicyProperties {

    private String model = "gemini-2.5-flash";

    private Map<String, String> stageModels = new LinkedHashMap<>();
}
