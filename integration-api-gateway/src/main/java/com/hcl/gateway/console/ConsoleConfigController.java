package com.hcl.gateway.console;

import com.hcl.gateway.config.ConsoleProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/console")
public class ConsoleConfigController {

    private final ConsoleProperties consoleProperties;

    public ConsoleConfigController(ConsoleProperties consoleProperties) {
        this.consoleProperties = consoleProperties;
    }

    @GetMapping("/config")
    public ConsoleUiConfig config() {
        ConsoleUiConfig config = new ConsoleUiConfig();
        config.setIntelligenceEnabled(consoleProperties.getIntelligence().isEnabled());
        return config;
    }
}
