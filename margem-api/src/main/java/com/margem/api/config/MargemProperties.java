package com.margem.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "margem")
public record MargemProperties(
        String armazenamento,
        List<String> corsOrigens,
        int maxCaracteresPorParte,
        String fonteAbnt
) {
}
