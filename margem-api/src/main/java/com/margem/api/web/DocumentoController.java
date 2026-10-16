package com.margem.api.web;

import com.margem.api.dominio.EscalaNota;
import com.margem.api.dominio.Enums.TipoTrabalho;
import com.margem.api.servico.DocumentoService;
import com.margem.api.servico.Erros;
import com.margem.api.web.dto.Requisicoes;
import com.margem.api.web.dto.Respostas.DocumentoDetalhe;
import com.margem.api.web.dto.Respostas.DocumentoResumo;
import com.margem.api.web.dto.Respostas.OrientacaoDto;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Identificação do aluno: no MVP o front gera um UUID, guarda no localStorage e manda
 * em todo pedido no cabeçalho X-Cliente-Id. Quando houver login, troque por
 * Spring Security + JWT e use o id do usuário no lugar.
 */
@RestController
@RequestMapping("/api/documentos")
public class DocumentoController {

    static final String CLIENTE = "X-Cliente-Id";

    private final DocumentoService service;

    public DocumentoController(DocumentoService service) {
        this.service = service;
    }

    /** Tela "Novo documento" → botão "Analisar documento". Responde 202 e a análise segue em segundo plano. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentoDetalhe> enviar(
            @RequestHeader(CLIENTE) String clienteId,
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestParam(defaultValue = "TCC") TipoTrabalho tipoTrabalho,
            @RequestParam(required = false) String curso,
            @RequestParam(defaultValue = "DEZ") EscalaNota escalaNota) throws IOException {
        DocumentoDetalhe criado = service.criar(validarCliente(clienteId), arquivo, tipoTrabalho, curso, escalaNota);
        return ResponseEntity.accepted()
                .header(HttpHeaders.LOCATION, "/api/documentos/" + criado.id())
                .body(criado);
    }

    /** "Seus trabalhos" / "Meus trabalhos". */
    @GetMapping
    public List<DocumentoResumo> listar(@RequestHeader(CLIENTE) String clienteId,
                                        @RequestParam(defaultValue = "50") int limite) {
        return service.listar(validarCliente(clienteId), limite);
    }

    /** Relatório de qualidade. Enquanto status for ANALISANDO, o front consulta a cada ~3 s. */
    @GetMapping("/{id}")
    public DocumentoDetalhe detalhe(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id) {
        return service.detalhe(validarCliente(clienteId), id);
    }

    /** Troca a escala de exibição (I/R/B/MB, 0 a 10, 0 a 100) sem nova análise. */
    @PatchMapping("/{id}")
    public DocumentoDetalhe atualizar(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id,
                                      @Valid @RequestBody Requisicoes.AtualizarDocumento corpo) {
        return service.atualizarEscala(validarCliente(clienteId), id, corpo.escalaNota());
    }

    /** "Marcar como resolvido". */
    @PatchMapping("/{id}/orientacoes/{orientacaoId}")
    public OrientacaoDto marcar(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id,
                                @PathVariable UUID orientacaoId,
                                @Valid @RequestBody Requisicoes.AtualizarOrientacao corpo) {
        return service.marcarOrientacao(validarCliente(clienteId), id, orientacaoId, corpo.resolvida());
    }

    /** Refaz a análise (ex.: depois de um erro temporário). */
    @PostMapping("/{id}/reanalise")
    public ResponseEntity<DocumentoDetalhe> reanalisar(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id) {
        String cliente = validarCliente(clienteId);
        service.reanalisar(cliente, id);
        return ResponseEntity.accepted().body(service.detalhe(cliente, id));
    }

    /** Etapa 4 "Formatar ABNT": gera a cópia .docx e devolve a lista do que mudou. */
    @PostMapping("/{id}/formatacao")
    public DocumentoDetalhe formatar(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id,
                                     @RequestBody(required = false) Requisicoes.Formatar corpo) throws IOException {
        boolean aplicar = corpo == null || corpo.aplicar();
        return service.formatar(validarCliente(clienteId), id, aplicar);
    }

    /** Download da cópia formatada. */
    @GetMapping("/{id}/formatacao/arquivo")
    public ResponseEntity<Resource> baixar(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id) {
        DocumentoService.ArquivoFormatado arq = service.arquivoFormatado(validarCliente(clienteId), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(arq.nome(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(arq.caminho()));
    }

    /** "Você pode apagar o arquivo a qualquer momento, em Meus trabalhos." Apaga registro e arquivos. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apagar(@RequestHeader(CLIENTE) String clienteId, @PathVariable UUID id) {
        service.apagar(validarCliente(clienteId), id);
    }

    private static String validarCliente(String clienteId) {
        if (clienteId == null || !clienteId.matches("[A-Za-z0-9-]{8,100}")) {
            throw new Erros.Invalido("Cabeçalho X-Cliente-Id ausente ou inválido.");
        }
        return clienteId;
    }
}
