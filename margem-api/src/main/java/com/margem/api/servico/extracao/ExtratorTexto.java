package com.margem.api.servico.extracao;

import com.margem.api.dominio.Enums.Formato;
import com.margem.api.servico.extracao.DocumentoExtraido.Paragrafo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lê .docx e .pdf e devolve parágrafos com página.
 *
 * <p>No .docx não existe "página" no arquivo; o Word grava marcas de quebra
 * (lastRenderedPageBreak) ao salvar, e é delas que vem a página. Quando o arquivo
 * não tem essas marcas (ex.: exportado do Google Docs), a página é estimada por
 * quantidade de caracteres.</p>
 */
@Component
public class ExtratorTexto {

    /** Média de caracteres por página A4 em fonte 12 e espaçamento 1,5. */
    static final int CARACTERES_POR_PAGINA = 2600;

    /** Teto de páginas de um PDF: evita que um arquivo pequeno em disco consuma CPU e memória demais. */
    static final int MAX_PAGINAS_PDF = 800;

    static {
        // Proteção contra "zip bomb" em .docx: o POI recusa entradas que expandem demais.
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(150L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(30L * 1024 * 1024);
    }

    public DocumentoExtraido extrair(Path arquivo, Formato formato) throws IOException {
        try (InputStream in = Files.newInputStream(arquivo)) {
            return formato == Formato.DOCX ? extrairDocx(in) : extrairPdf(arquivo);
        }
    }

    // ---------------------------------------------------------------- DOCX

    public DocumentoExtraido extrairDocx(InputStream in) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            List<XWPFParagraph> paragrafos = doc.getParagraphs();
            boolean temMarcas = paragrafos.stream().anyMatch(ExtratorTexto::comecaNovaPagina);

            List<Paragrafo> resultado = new ArrayList<>();
            int pagina = 1;
            int posicao = 0;
            int caracteresNaPagina = 0;

            for (int i = 0; i < paragrafos.size(); i++) {
                XWPFParagraph p = paragrafos.get(i);
                String texto = p.getText().replace(' ', ' ').trim();

                if (temMarcas) {
                    if (comecaNovaPagina(p) && i > 0) {
                        pagina++;
                        posicao = 0;
                    }
                } else if (caracteresNaPagina + texto.length() > CARACTERES_POR_PAGINA && caracteresNaPagina > 0) {
                    pagina++;
                    posicao = 0;
                    caracteresNaPagina = 0;
                }
                if (texto.isEmpty()) continue;

                posicao++;
                caracteresNaPagina += texto.length();
                resultado.add(new Paragrafo(i, pagina, posicao, texto, ehTitulo(doc, p)));
            }
            return new DocumentoExtraido(resultado, pagina, fatosFormatacao(doc, paragrafos));
        }
    }

    static boolean comecaNovaPagina(XWPFParagraph p) {
        if (p.isPageBreak()) return true;
        String xml = p.getCTP().xmlText();
        return xml.contains("lastRenderedPageBreak") || xml.contains("w:type=\"page\"");
    }

    /** Título = estilo de título do Word (Heading 1, Título 1...) ou outline level definido. */
    public static boolean ehTitulo(XWPFDocument doc, XWPFParagraph p) {
        return nivelTitulo(doc, p) > 0;
    }

    /** 0 quando não é título; 1..9 para o nível. */
    public static int nivelTitulo(XWPFDocument doc, XWPFParagraph p) {
        String styleId = p.getStyleID();
        if (styleId == null) return 0;
        String nome = styleId;
        if (doc.getStyles() != null) {
            XWPFStyle estilo = doc.getStyles().getStyle(styleId);
            if (estilo != null && estilo.getName() != null) nome = estilo.getName();
        }
        String n = nome.toLowerCase(Locale.ROOT).replace(" ", "");
        String id = styleId.toLowerCase(Locale.ROOT);
        boolean titulo = n.startsWith("heading") || n.startsWith("título") || n.startsWith("titulo")
                || id.startsWith("heading") || id.startsWith("ttulo") || id.startsWith("titulo");
        if (!titulo) return 0;
        String digitos = n.replaceAll("\\D", "");
        if (digitos.isEmpty()) digitos = id.replaceAll("\\D", "");
        return digitos.isEmpty() ? 1 : Math.max(1, Integer.parseInt(digitos.substring(0, 1)));
    }

    /** Resume a formatação real do arquivo em linguagem simples, para a IA avaliar a ABNT. */
    private String fatosFormatacao(XWPFDocument doc, List<XWPFParagraph> paragrafos) {
        StringBuilder sb = new StringBuilder();

        CTSectPr sect = doc.getDocument().getBody().getSectPr();
        if (sect != null && sect.getPgMar() != null) {
            CTPageMar m = sect.getPgMar();
            sb.append(String.format(Locale.ROOT,
                    "Margens (cm): superior %.1f, inferior %.1f, esquerda %.1f, direita %.1f. ABNT pede 3, 2, 3, 2.%n",
                    cm(m.getTop()), cm(m.getBottom()), cm(m.getLeft()), cm(m.getRight())));
        }

        List<XWPFParagraph> corpo = paragrafos.stream()
                .filter(p -> p.getText().trim().length() > 120 && !ehTitulo(doc, p))
                .toList();
        if (!corpo.isEmpty()) {
            List<XWPFRun> runs = corpo.stream().flatMap(p -> p.getRuns().stream()).toList();
            sb.append("Fonte mais usada no corpo: ")
                    .append(maisFrequente(runs, r -> Objects.requireNonNullElse(r.getFontFamily(), "padrão do estilo")))
                    .append(", tamanho ")
                    .append(maisFrequente(runs, r -> r.getFontSizeAsDouble() == null ? "padrão do estilo" : r.getFontSizeAsDouble() + " pt"))
                    .append(".\n");
            sb.append("Alinhamento mais usado nos parágrafos do corpo: ")
                    .append(maisFrequente(corpo, p -> p.getAlignment().name()))
                    .append(" (ABNT: justificado = BOTH).\n");
            sb.append("Espaçamento entre linhas mais usado: ")
                    .append(maisFrequente(corpo, p -> p.getSpacingBetween() <= 0 ? "padrão do estilo" : String.valueOf(p.getSpacingBetween())))
                    .append(" (ABNT: 1,5).\n");
            long comRecuo = corpo.stream().filter(p -> p.getIndentationFirstLine() > 0).count();
            sb.append("Parágrafos do corpo com recuo na primeira linha: ")
                    .append(comRecuo).append(" de ").append(corpo.size()).append(".\n");
        }
        sb.append("Títulos de seção marcados com estilo de título: ")
                .append(paragrafos.stream().filter(p -> ehTitulo(doc, p)).count()).append(".\n");
        return sb.toString();
    }

    private static double cm(Object twips) {
        if (twips == null) return 0;
        return new BigInteger(twips.toString()).doubleValue() / 567.0;
    }

    private static <T> String maisFrequente(List<T> itens, Function<T, String> chave) {
        return itens.stream()
                .collect(Collectors.groupingBy(chave, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("não identificado");
    }

    // ---------------------------------------------------------------- PDF

    private DocumentoExtraido extrairPdf(Path arquivo) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(arquivo.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setParagraphStart("\n¶");
            List<Paragrafo> resultado = new ArrayList<>();
            int indice = 0;
            int total = pdf.getNumberOfPages();
            if (total > MAX_PAGINAS_PDF) throw new IOException("O PDF tem páginas demais para analisar.");
            for (int pagina = 1; pagina <= total; pagina++) {
                stripper.setStartPage(pagina);
                stripper.setEndPage(pagina);
                String texto = stripper.getText(pdf);
                int posicao = 0;
                for (String bloco : texto.split("¶")) {
                    String limpo = bloco.replaceAll("\\s*\\n\\s*", " ").trim();
                    if (limpo.isEmpty() || limpo.matches("\\d{1,4}")) continue; // ignora número de página solto
                    posicao++;
                    boolean titulo = limpo.length() < 90 && limpo.equals(limpo.toUpperCase(Locale.ROOT))
                            && limpo.chars().anyMatch(Character::isLetter);
                    resultado.add(new Paragrafo(indice++, pagina, posicao, limpo, titulo));
                }
            }
            if (resultado.stream().mapToInt(p -> p.texto().length()).sum() < 200) {
                throw new IOException("O PDF parece ser uma imagem escaneada, sem texto selecionável.");
            }
            return new DocumentoExtraido(resultado, total, "");
        }
    }
}
