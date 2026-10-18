package com.margem.api.web;

import com.margem.api.dominio.EscalaNota;
import com.margem.api.dominio.Enums.TipoTrabalho;
import com.margem.api.servico.DocumentoService;
import com.margem.api.servico.Erros;
import com.margem.api.web.dto.Requisicoes;
import com.margem.api.web.dto.Respostas.DocumentoDetalhe;
import com.margem.api.web.dto.Respostas.DocumentoResumo;
import com.margem.api.web.dto.Respostas.OrientacaoDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documentos")
@Validated
@Tag(name = "Documentos", description = "Trabalhos do usuário logado. Só o dono acessa; os demais recebem 404.")
public class DocumentoController {

    private final DocumentoService service;

    public DocumentoController(DocumentoService service) {
        this.service = service;
    }

    /** Tela "Novo documento" → botão "Analisar documento". Responde 202 e a análise segue em segundo plano. */
    @Operation(summary = "Envia o trabalho e inicia a análise (202)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentoDetalhe> enviar(
            Authentication autenticacao,
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestParam(defaultValue = "TCC") TipoTrabalho tipoTrabalho,
            @RequestParam(required = false) @Size(max = 150, message = "O curso pode ter até 150 caracteres.") String curso,
            @RequestParam(defaultValue = "DEZ") EscalaNota escalaNota) throws IOException {
        DocumentoDetalhe criado = service.criar(dono(autenticacao), arquivo, tipoTrabalho, curso, escalaNota);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/documentos/" + criado.id())
                .body(criado);
    }

    /** "Seus trabalhos" / "Meus trabalhos". */
    @Operation(summary = "Lista os trabalhos do usuário")
    @GetMapping
    public List<DocumentoResumo> listar(Authentication autenticacao,
                                        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limite) {
        return service.listar(dono(autenticacao), limite);
    }

    /** Relatório de qualidade. Enquanto status for ANALISANDO, o front consulta a cada ~3 s. */
    @Operation(summary = "Relatório do trabalho (use também para acompanhar a análise)")
    @GetMapping("/{id}")
    public DocumentoDetalhe detalhe(Authentication autenticacao, @PathVariable UUID id) {
        return service.detalhe(dono(autenticacao), id);
    }

    /** Troca a escala de exibição (I/R/B/MB, 0 a 10, 0 a 100) sem nova análise. */
    @Operation(summary = "Troca a escala de exibição da nota")
    @PatchMapping("/{id}")
    public DocumentoDetalhe atualizar(Authentication autenticacao, @PathVariable UUID id,
                                      @Valid @RequestBody Requisicoes.AtualizarDocumento corpo) {
        return service.atualizarEscala(dono(autenticacao), id, corpo.escalaNota());
    }

    /** "Marcar como resolvido". */
    @Operation(summary = "Marca uma orientação como resolvida")
    @PatchMapping("/{id}/orientacoes/{orientacaoId}")
    public OrientacaoDto marcar(Authentication autenticacao, @PathVariable UUID id,
                                @PathVariable UUID orientacaoId,
                                @Valid @RequestBody Requisicoes.AtualizarOrientacao corpo) {
        return service.marcarOrientacao(dono(autenticacao), id, orientacaoId, corpo.resolvida());
    }

    /** Refaz a análise (ex.: depois de um erro temporário). */
    @Operation(summary = "Refaz a análise")
    @PostMapping("/{id}/reanalise")
    public ResponseEntity<DocumentoDetalhe> reanalisar(Authentication autenticacao, @PathVariable UUID id) {
        UUID usuario = dono(autenticacao);
        service.reanalisar(usuario, id);
        return ResponseEntity.accepted().body(service.detalhe(usuario, id));
    }

    /** Etapa 4 "Formatar ABNT": gera a cópia .docx e devolve a lista do que mudou. */
    @Operation(summary = "Gera a cópia formatada em ABNT")
    @PostMapping("/{id}/formatacao")
    public DocumentoDetalhe formatar(Authentication autenticacao, @PathVariable UUID id,
                                     @RequestBody(required = false) Requisicoes.Formatar corpo) throws IOException {
        boolean aplicar = corpo == null || corpo.aplicar();
        return service.formatar(dono(autenticacao), id, aplicar);
    }

    /** Download da cópia formatada. */
    @Operation(summary = "Baixa a cópia formatada (.docx)")
    @GetMapping("/{id}/formatacao/arquivo")
    public ResponseEntity<Resource> baixar(Authentication autenticacao, @PathVariable UUID id) {
        DocumentoService.ArquivoFormatado arq = service.arquivoFormatado(dono(autenticacao), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(arq.nome(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(arq.caminho()));
    }

    /** "Você pode apagar o arquivo a qualquer momento, em Meus trabalhos." Apaga registro e arquivos. */
    @Operation(summary = "Apaga o trabalho e os arquivos")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagar(Authentication autenticacao, @PathVariable UUID id) {
        service.apagar(dono(autenticacao), id);
    }

    /** O dono vem do token validado (claim "sub"), nunca de parâmetro ou cabeçalho enviado pelo cliente. */
    private static UUID dono(Authentication autenticacao) {
        return UUID.fromString(autenticacao.getName());
    }
}
