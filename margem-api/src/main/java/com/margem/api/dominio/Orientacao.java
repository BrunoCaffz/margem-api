package com.margem.api.dominio;

import com.margem.api.dominio.Enums.Categoria;
import com.margem.api.dominio.Enums.Severidade;
import jakarta.persistence.*;

import java.util.UUID;

/** Um item do relatório: o que está errado, por que importa e como ajustar. */
@Entity
@Table(name = "orientacao")
public class Orientacao {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documento_id")
    private Documento documento;

    private int ordem;

    @Enumerated(EnumType.STRING)
    private Severidade severidade;

    @Enumerated(EnumType.STRING)
    private Categoria categoria;

    @Column(length = 300)
    private String titulo;

    private Integer pagina;
    private Integer paragrafo;
    /** Posição do parágrafo no .docx, usada para aplicar correções na cópia formatada. */
    private Integer paragrafoIndice;

    @Column(length = 2000)
    private String trecho;

    @Column(length = 2000)
    private String porQueImporta;

    @Column(length = 2000)
    private String comoAjustar;

    @Column(length = 1000)
    private String correcaoOriginal;

    @Column(length = 1000)
    private String correcaoSugerida;

    private boolean resolvida;

    protected Orientacao() {
    }

    public Orientacao(int ordem, Severidade severidade, Categoria categoria, String titulo,
                      Integer pagina, Integer paragrafo, Integer paragrafoIndice, String trecho,
                      String porQueImporta, String comoAjustar,
                      String correcaoOriginal, String correcaoSugerida) {
        this.ordem = ordem;
        this.severidade = severidade;
        this.categoria = categoria;
        this.titulo = titulo;
        this.pagina = pagina;
        this.paragrafo = paragrafo;
        this.paragrafoIndice = paragrafoIndice;
        this.trecho = trecho;
        this.porQueImporta = porQueImporta;
        this.comoAjustar = comoAjustar;
        this.correcaoOriginal = correcaoOriginal;
        this.correcaoSugerida = correcaoSugerida;
    }

    void setDocumento(Documento documento) { this.documento = documento; }
    public void setResolvida(boolean resolvida) { this.resolvida = resolvida; }

    public UUID getId() { return id; }
    public int getOrdem() { return ordem; }
    public Severidade getSeveridade() { return severidade; }
    public Categoria getCategoria() { return categoria; }
    public String getTitulo() { return titulo; }
    public Integer getPagina() { return pagina; }
    public Integer getParagrafo() { return paragrafo; }
    public Integer getParagrafoIndice() { return paragrafoIndice; }
    public String getTrecho() { return trecho; }
    public String getPorQueImporta() { return porQueImporta; }
    public String getComoAjustar() { return comoAjustar; }
    public String getCorrecaoOriginal() { return correcaoOriginal; }
    public String getCorrecaoSugerida() { return correcaoSugerida; }
    public boolean isResolvida() { return resolvida; }
}
