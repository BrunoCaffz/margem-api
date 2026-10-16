package com.margem.api.servico;

import com.margem.api.config.MargemProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Guarda arquivos em disco local. Para produção em várias instâncias, troque por
 * S3/R2/GCS mantendo a mesma interface (salvar, abrir, apagar).
 */
@Component
public class Armazenamento {

    private final Path raiz;

    public Armazenamento(MargemProperties props) throws IOException {
        this.raiz = Path.of(props.armazenamento()).toAbsolutePath().normalize();
        Files.createDirectories(raiz);
    }

    public String salvar(UUID documentoId, String sufixo, InputStream conteudo) throws IOException {
        Path destino = resolver(documentoId + "-" + sufixo);
        Files.copy(conteudo, destino, StandardCopyOption.REPLACE_EXISTING);
        return raiz.relativize(destino).toString();
    }

    public String salvar(UUID documentoId, String sufixo, byte[] conteudo) throws IOException {
        Path destino = resolver(documentoId + "-" + sufixo);
        Files.write(destino, conteudo);
        return raiz.relativize(destino).toString();
    }

    public Path caminho(String relativo) {
        return resolver(relativo);
    }

    public void apagar(String relativo) {
        if (relativo == null) return;
        try {
            Files.deleteIfExists(resolver(relativo));
        } catch (IOException ignored) {
            // arquivo já removido ou inacessível: não impede apagar o registro
        }
    }

    private Path resolver(String nome) {
        Path p = raiz.resolve(nome).normalize();
        if (!p.startsWith(raiz)) throw new IllegalArgumentException("Caminho inválido");
        return p;
    }
}
