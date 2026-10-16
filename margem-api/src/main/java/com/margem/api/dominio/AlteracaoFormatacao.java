package com.margem.api.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Uma mudança feita na cópia ABNT. O front usa a lista para pintar de amarelo
 * (marca-texto) só os trechos cuja formatação mudou.
 */
@Embeddable
public class AlteracaoFormatacao {

    private int paragrafoIndice;
    private Integer pagina;

    @Column(length = 300)
    private String trecho;

    @Column(length = 1000)
    private String descricao;

    protected AlteracaoFormatacao() {
    }

    public AlteracaoFormatacao(int paragrafoIndice, Integer pagina, String trecho, String descricao) {
        this.paragrafoIndice = paragrafoIndice;
        this.pagina = pagina;
        this.trecho = trecho;
        this.descricao = descricao;
    }

    public int getParagrafoIndice() { return paragrafoIndice; }
    public Integer getPagina() { return pagina; }
    public String getTrecho() { return trecho; }
    public String getDescricao() { return descricao; }
}
