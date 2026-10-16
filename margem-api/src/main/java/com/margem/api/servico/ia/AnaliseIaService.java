package com.margem.api.servico.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.margem.api.config.MargemProperties;
import com.margem.api.dominio.Enums.TipoTrabalho;
import com.margem.api.servico.extracao.DocumentoExtraido;
import com.margem.api.servico.extracao.DocumentoExtraido.Paragrafo;
import com.margem.api.servico.ia.RespostaAnalise.Notas;
import com.margem.api.servico.ia.RespostaAnalise.OrientacaoIa;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Monta o pedido para a IA e junta as respostas.
 *
 * <p>Um TCC típico (40 a 80 páginas) cabe numa chamada só. Trabalhos maiores são
 * divididos em partes; cada parte recebe o sumário do trabalho inteiro para que a
 * avaliação de estrutura considere o todo, e as notas são combinadas pela média
 * ponderada pelo tamanho de cada parte.</p>
 */
@Service
public class AnaliseIaService {

    static final int MAX_ORIENTACOES_TOTAL = 40;

    private final OpenAiClient openAi;
    private final MargemProperties props;
    private final String instrucoesBase;
    private final JsonNode schema;

    public AnaliseIaService(OpenAiClient openAi, MargemProperties props, ObjectMapper mapper) {
        this.openAi = openAi;
        this.props = props;
        try {
            this.instrucoesBase = new ClassPathResource("prompts/analise-sistema.md")
                    .getContentAsString(StandardCharsets.UTF_8);
            this.schema = mapper.readTree(new ClassPathResource("prompts/analise-schema.json").getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível carregar os prompts", e);
        }
    }

    public RespostaAnalise analisar(DocumentoExtraido doc, TipoTrabalho tipo, String curso) {
        List<List<Paragrafo>> partes = dividir(doc.paragrafos(), props.maxCaracteresPorParte());
        if (partes.isEmpty()) {
            throw new IllegalArgumentException("Não encontramos texto no documento.");
        }

        int maxPorParte = Math.max(10, MAX_ORIENTACOES_TOTAL / partes.size() + 5);
        String instrucoes = instrucoesBase.replace("{{MAX_ORIENTACOES}}", String.valueOf(maxPorParte));

        List<RespostaAnalise> respostas = new ArrayList<>();
        List<Integer> pesos = new ArrayList<>();
        for (int i = 0; i < partes.size(); i++) {
            List<Paragrafo> parte = partes.get(i);
            String conteudo = montarConteudo(doc, parte, tipo, curso, i + 1, partes.size());
            respostas.add(openAi.completarJson(instrucoes, conteudo, "analise_trabalho", schema, RespostaAnalise.class));
            pesos.add(parte.stream().mapToInt(p -> p.texto().length()).sum());
        }
        return combinar(respostas, pesos);
    }

    String montarConteudo(DocumentoExtraido doc, List<Paragrafo> parte, TipoTrabalho tipo, String curso,
                          int numeroParte, int totalPartes) {
        StringBuilder sb = new StringBuilder();
        sb.append("TIPO DE TRABALHO: ").append(tipo.rotulo).append('\n');
        if (curso != null && !curso.isBlank()) sb.append("CURSO OU ÁREA: ").append(curso).append('\n');
        sb.append("TOTAL DE PÁGINAS: ").append(doc.totalPaginas()).append('\n');

        if (totalPartes > 1) {
            sb.append("\nEste trabalho é longo e foi dividido. Você está avaliando a PARTE ")
                    .append(numeroParte).append(" de ").append(totalPartes)
                    .append(". Dê orientações só sobre esta parte; para a nota de estrutura considere o sumário completo.\n");
        }

        List<String> sumario = doc.sumario();
        if (!sumario.isEmpty()) {
            sb.append("\nSUMÁRIO DO TRABALHO INTEIRO (títulos detectados):\n");
            sumario.forEach(t -> sb.append("- ").append(t).append('\n'));
        }

        if (doc.fatosFormatacao() != null && !doc.fatosFormatacao().isBlank()) {
            sb.append("\nFATOS DE FORMATAÇÃO MEDIDOS NO ARQUIVO:\n").append(doc.fatosFormatacao());
        }

        sb.append("\n<<<TEXTO DO TRABALHO>>>\n");
        for (Paragrafo p : parte) {
            sb.append("[p.").append(p.pagina()).append(" §").append(p.posicaoNaPagina()).append("] ");
            if (p.titulo()) sb.append("[TÍTULO] ");
            sb.append(p.texto()).append('\n');
        }
        sb.append("<<<FIM DO TEXTO>>>\n");
        return sb.toString();
    }

    static List<List<Paragrafo>> dividir(List<Paragrafo> paragrafos, int maxCaracteres) {
        List<List<Paragrafo>> partes = new ArrayList<>();
        List<Paragrafo> atual = new ArrayList<>();
        int tamanho = 0;
        for (Paragrafo p : paragrafos) {
            // Prefere quebrar num título para não cortar uma seção ao meio
            boolean cheio = tamanho + p.texto().length() > maxCaracteres;
            boolean quaseCheioETitulo = p.titulo() && tamanho > maxCaracteres * 0.75;
            if (!atual.isEmpty() && (cheio || quaseCheioETitulo)) {
                partes.add(atual);
                atual = new ArrayList<>();
                tamanho = 0;
            }
            atual.add(p);
            tamanho += p.texto().length();
        }
        if (!atual.isEmpty()) partes.add(atual);
        return partes;
    }

    static RespostaAnalise combinar(List<RespostaAnalise> respostas, List<Integer> pesos) {
        if (respostas.size() == 1) {
            RespostaAnalise r = respostas.getFirst();
            return new RespostaAnalise(r.resumo(), limitar(r.notas()), ordenar(r.orientacoes()));
        }
        double total = pesos.stream().mapToInt(Integer::intValue).sum();
        double e = 0, a = 0, c = 0, cr = 0, f = 0;
        List<OrientacaoIa> todas = new ArrayList<>();
        StringBuilder resumo = new StringBuilder();
        for (int i = 0; i < respostas.size(); i++) {
            RespostaAnalise r = respostas.get(i);
            double w = pesos.get(i) / total;
            e += r.notas().estrutura() * w;
            a += r.notas().argumentacao() * w;
            c += r.notas().clareza() * w;
            cr += r.notas().citacoesReferencias() * w;
            f += r.notas().formatacaoAbnt() * w;
            todas.addAll(r.orientacoes());
            if (i == 0) resumo.append(r.resumo());
        }
        return new RespostaAnalise(resumo.toString(), limitar(new Notas(e, a, c, cr, f)), ordenar(todas));
    }

    private static List<OrientacaoIa> ordenar(List<OrientacaoIa> lista) {
        return lista.stream()
                // ordenação estável: críticas primeiro, mantendo a ordem de importância dada pela IA
                .sorted(Comparator.comparing((OrientacaoIa o) -> "CRITICO".equals(o.severidade()) ? 0 : 1))
                .limit(MAX_ORIENTACOES_TOTAL)
                .toList();
    }

    private static Notas limitar(Notas n) {
        return new Notas(arredondar(n.estrutura()), arredondar(n.argumentacao()), arredondar(n.clareza()),
                arredondar(n.citacoesReferencias()), arredondar(n.formatacaoAbnt()));
    }

    private static double arredondar(double v) {
        return Math.round(Math.max(0, Math.min(10, v)) * 10) / 10.0;
    }
}
