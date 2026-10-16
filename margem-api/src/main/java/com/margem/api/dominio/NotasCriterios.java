package com.margem.api.dominio;

import jakarta.persistence.Embeddable;

/** Notas de 0 a 10 por critério, na ordem em que aparecem no relatório. */
@Embeddable
public class NotasCriterios {

    private Double estrutura;
    private Double argumentacao;
    private Double clareza;
    private Double citacoesReferencias;
    private Double formatacaoAbnt;

    protected NotasCriterios() {
    }

    public NotasCriterios(double estrutura, double argumentacao, double clareza,
                          double citacoesReferencias, double formatacaoAbnt) {
        this.estrutura = estrutura;
        this.argumentacao = argumentacao;
        this.clareza = clareza;
        this.citacoesReferencias = citacoesReferencias;
        this.formatacaoAbnt = formatacaoAbnt;
    }

    public double media() {
        return (estrutura + argumentacao + clareza + citacoesReferencias + formatacaoAbnt) / 5.0;
    }

    public Double getEstrutura() { return estrutura; }
    public Double getArgumentacao() { return argumentacao; }
    public Double getClareza() { return clareza; }
    public Double getCitacoesReferencias() { return citacoesReferencias; }
    public Double getFormatacaoAbnt() { return formatacaoAbnt; }
}
