package com.margem.api.servico.extracao;

import java.util.List;
import java.util.Optional;

/**
 * Texto do trabalho já dividido em parágrafos, cada um com página e posição na página,
 * para que a IA possa citar "Página 14, parágrafo 2" como no relatório.
 *
 * @param fatosFormatacao medições objetivas do .docx (margens, fonte, espaçamento) que a IA
 *                        não consegue ver só pelo texto; vazio para PDF
 */
public record DocumentoExtraido(
        List<Paragrafo> paragrafos,
        int totalPaginas,
        String fatosFormatacao
) {

    /**
     * @param indice          posição do parágrafo no corpo do .docx (getParagraphs) ou sequencial no PDF
     * @param pagina          página estimada (1..n)
     * @param posicaoNaPagina parágrafo não vazio dentro da página (1..n)
     * @param titulo          true quando é um título de seção
     */
    public record Paragrafo(int indice, int pagina, int posicaoNaPagina, String texto, boolean titulo) {
    }

    public List<String> sumario() {
        return paragrafos.stream().filter(Paragrafo::titulo)
                .map(p -> p.texto() + " (p. " + p.pagina() + ")")
                .toList();
    }

    public Optional<Paragrafo> localizar(Integer pagina, Integer posicao) {
        if (pagina == null || posicao == null) return Optional.empty();
        return paragrafos.stream()
                .filter(p -> p.pagina() == pagina && p.posicaoNaPagina() == posicao)
                .findFirst();
    }

    public Optional<Paragrafo> localizarPorTrecho(String trecho) {
        if (trecho == null || trecho.isBlank()) return Optional.empty();
        String alvo = normalizar(trecho);
        return paragrafos.stream().filter(p -> normalizar(p.texto()).contains(alvo)).findFirst();
    }

    static String normalizar(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
