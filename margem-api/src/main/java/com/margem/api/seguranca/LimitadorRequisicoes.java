package com.margem.api.seguranca;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.margem.api.config.SegurancaProperties.Limite;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token bucket por chave. Os contadores ficam em memória desta instância e expiram sozinhos.
 * Com várias instâncias, troque o cache por um Bucket4j com Redis/JDBC para o limite ser global.
 */
@Component
public class LimitadorRequisicoes {

    private final Cache<String, Bucket> baldes = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(2))
            .maximumSize(200_000)
            .build();

    /** @return 0 se a requisição passou, ou quantos segundos faltam para poder tentar de novo. */
    public long consumir(String chave, Limite limite) {
        Bucket balde = baldes.get(chave, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limite.capacidade())
                        .refillIntervally(limite.capacidade(), limite.janela()).build())
                .build());
        ConsumptionProbe sonda = balde.tryConsumeAndReturnRemaining(1);
        if (sonda.isConsumed()) return 0;
        return Math.max(1, (sonda.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
    }
}
