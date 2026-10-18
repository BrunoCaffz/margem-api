package com.margem.api.dominio;

import com.margem.api.dominio.Enums.Formato;
import com.margem.api.dominio.Enums.StatusDocumento;
import com.margem.api.dominio.Enums.TipoTrabalho;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "documento", indexes = @Index(name = "idx_documento_usuario", columnList = "usuario_id,criado_em"))
public class Documento {

    @Id
    private UUID id = UUID.randomUUID();

    /** Dono do documento: o id do usuário autenticado (claim "sub" do token), nunca um valor enviado pelo cliente. */
    @Column(nullable = false)
    private UUID usuarioId;

    @Column(nullable = false, length = 255)
    private String nomeArquivo;

    @Enumerated(EnumType.STRING)
    private Formato formato;

    @Enumerated(EnumType.STRING)
    private TipoTrabalho tipoTrabalho;

    @Column(length = 150)
    private String curso;

    @Enumerated(EnumType.STRING)
    private EscalaNota escalaNota;

    @Enumerated(EnumType.STRING)
    private StatusDocumento status = StatusDocumento.RECEBIDO;

    @Column(length = 500)
    private String erro;

    private Instant criadoEm = Instant.now();
    private Instant analisadoEm;

    private String caminhoArquivo;
    private long tamanhoBytes;
    private Integer totalPaginas;

    /** Nota geral, sempre de 0 a 10. */
    private Double notaGeral;

    @Embedded
    private NotasCriterios notas;

    @Column(length = 4000)
    private String resumo;

    @OneToMany(mappedBy = "documento", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordem ASC")
    private List<Orientacao> orientacoes = new ArrayList<>();

    private String caminhoFormatado;
    private Instant formatadoEm;
    private int correcoesAplicadas;

    @ElementCollection
    @CollectionTable(name = "alteracao_formatacao", joinColumns = @JoinColumn(name = "documento_id"))
    private List<AlteracaoFormatacao> alteracoesFormatacao = new ArrayList<>();

    protected Documento() {
    }

    public Documento(UUID usuarioId, String nomeArquivo, Formato formato, TipoTrabalho tipoTrabalho,
                     String curso, EscalaNota escalaNota, String caminhoArquivo, long tamanhoBytes) {
        this.usuarioId = usuarioId;
        this.nomeArquivo = nomeArquivo;
        this.formato = formato;
        this.tipoTrabalho = tipoTrabalho;
        this.curso = curso;
        this.escalaNota = escalaNota;
        this.caminhoArquivo = caminhoArquivo;
        this.tamanhoBytes = tamanhoBytes;
    }

    public void definirArquivo(String caminhoArquivo) {
        this.caminhoArquivo = caminhoArquivo;
    }

    public void iniciarAnalise() {
        this.status = StatusDocumento.ANALISANDO;
        this.erro = null;
    }

    public void concluirAnalise(NotasCriterios notas, String resumo, List<Orientacao> novas, int totalPaginas) {
        this.notas = notas;
        this.notaGeral = Math.round(notas.media() * 10) / 10.0;
        this.resumo = resumo != null && resumo.length() > 4000 ? resumo.substring(0, 4000) : resumo;
        this.totalPaginas = totalPaginas;
        this.orientacoes.clear();
        novas.forEach(o -> {
            o.setDocumento(this);
            this.orientacoes.add(o);
        });
        this.status = StatusDocumento.CONCLUIDO;
        this.analisadoEm = Instant.now();
    }

    public void falhar(String mensagem) {
        this.status = StatusDocumento.ERRO;
        this.erro = mensagem;
    }

    public void registrarFormatacao(String caminho, List<AlteracaoFormatacao> alteracoes, int correcoesAplicadas) {
        this.caminhoFormatado = caminho;
        this.formatadoEm = Instant.now();
        this.correcoesAplicadas = correcoesAplicadas;
        this.alteracoesFormatacao.clear();
        this.alteracoesFormatacao.addAll(alteracoes);
    }

    public boolean podeFormatar() {
        return formato == Formato.DOCX && status == StatusDocumento.CONCLUIDO;
    }

    public void setEscalaNota(EscalaNota escalaNota) { this.escalaNota = escalaNota; }

    public UUID getId() { return id; }
    public UUID getUsuarioId() { return usuarioId; }
    public String getNomeArquivo() { return nomeArquivo; }
    public Formato getFormato() { return formato; }
    public TipoTrabalho getTipoTrabalho() { return tipoTrabalho; }
    public String getCurso() { return curso; }
    public EscalaNota getEscalaNota() { return escalaNota; }
    public StatusDocumento getStatus() { return status; }
    public String getErro() { return erro; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAnalisadoEm() { return analisadoEm; }
    public String getCaminhoArquivo() { return caminhoArquivo; }
    public long getTamanhoBytes() { return tamanhoBytes; }
    public Integer getTotalPaginas() { return totalPaginas; }
    public Double getNotaGeral() { return notaGeral; }
    public NotasCriterios getNotas() { return notas; }
    public String getResumo() { return resumo; }
    public List<Orientacao> getOrientacoes() { return orientacoes; }
    public String getCaminhoFormatado() { return caminhoFormatado; }
    public Instant getFormatadoEm() { return formatadoEm; }
    public int getCorrecoesAplicadas() { return correcoesAplicadas; }
    public List<AlteracaoFormatacao> getAlteracoesFormatacao() { return alteracoesFormatacao; }
}
