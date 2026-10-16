package com.margem.api.servico.abnt;

import com.margem.api.config.MargemProperties;
import com.margem.api.servico.extracao.ExtratorTexto;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;

/**
 * Aplica as regras de forma da NBR 14724 num .docx, sem usar IA (é determinístico,
 * rápido e não reescreve o texto do aluno):
 * <ul>
 *   <li>Papel A4; margens superior e esquerda 3 cm, inferior e direita 2 cm</li>
 *   <li>Corpo: fonte 12, espaçamento 1,5, justificado, recuo de 1,25 cm na primeira linha</li>
 *   <li>Citação longa (já recuada no original): recuo de 4 cm, fonte 10, espaçamento simples</li>
 *   <li>Títulos: sem recuo, alinhados à esquerda, negrito; seção primária em maiúsculas (NBR 6024)</li>
 *   <li>Referências: alinhadas à esquerda, espaçamento simples, uma linha em branco entre elas (NBR 6023)</li>
 *   <li>Número de página no canto superior direito, fonte 10</li>
 * </ul>
 * Parágrafos centralizados (capa, folha de rosto) mantêm o alinhamento; só a fonte é ajustada.
 * Opcionalmente aplica as correções de português sugeridas pela IA.
 */
@Component
public class FormatadorAbnt {

    // 1 cm = 567 twips
    static final int MARGEM_3CM = 1701;
    static final int MARGEM_2CM = 1134;
    static final int RECUO_1_25CM = 709;
    static final int RECUO_4CM = 2268;
    static final int RECUO_CITACAO_DETECTAR = 1000; // ~1,8 cm: original já tinha recuo grande
    static final int A4_LARGURA = 11906;
    static final int A4_ALTURA = 16838;

    private final String fonte;

    public FormatadorAbnt(MargemProperties props) {
        this.fonte = props.fonteAbnt() == null || props.fonteAbnt().isBlank() ? "Times New Roman" : props.fonteAbnt();
    }

    public record Correcao(Integer paragrafoIndice, String original, String sugerido) {
    }

    public record Alteracao(int paragrafoIndice, String trecho, String descricao) {
    }

    public record Resultado(byte[] arquivo, List<Alteracao> alteracoes, int correcoesAplicadas, int correcoesIgnoradas) {
    }

    public Resultado formatar(InputStream docx, List<Correcao> correcoes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(docx)) {
            List<Alteracao> alteracoes = new ArrayList<>();
            ajustarPagina(doc, alteracoes);

            int[] contagem = aplicarCorrecoes(doc, correcoes == null ? List.of() : correcoes, alteracoes);

            List<XWPFParagraph> paragrafos = doc.getParagraphs();
            boolean emReferencias = false;
            for (int i = 0; i < paragrafos.size(); i++) {
                XWPFParagraph p = paragrafos.get(i);
                String texto = p.getText().trim();
                if (texto.isEmpty()) continue;

                int nivel = ExtratorTexto.nivelTitulo(doc, p);
                if (nivel > 0) {
                    emReferencias = texto.toUpperCase(Locale.ROOT).replaceAll("[^A-ZÁÉÍÓÚÊÇ]", "").equals("REFERÊNCIAS");
                    registrar(alteracoes, i, texto, formatarTitulo(p, nivel));
                } else if (p.getAlignment() == ParagraphAlignment.CENTER) {
                    registrar(alteracoes, i, texto, ajustarFonte(p, 12));
                } else if (emReferencias) {
                    registrar(alteracoes, i, texto, formatarReferencia(p));
                } else if (p.getIndentationLeft() >= RECUO_CITACAO_DETECTAR) {
                    registrar(alteracoes, i, texto, formatarCitacaoLonga(p));
                } else {
                    registrar(alteracoes, i, texto, formatarCorpo(p));
                }
            }

            adicionarNumeroPagina(doc);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return new Resultado(out.toByteArray(), alteracoes, contagem[0], contagem[1]);
        }
    }

    // ---------------------------------------------------------------- página

    private void ajustarPagina(XWPFDocument doc, List<Alteracao> alteracoes) {
        CTBody body = doc.getDocument().getBody();
        CTSectPr sect = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();

        CTPageSz sz = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        CTPageMar m = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();

        boolean mudou = !Objects.equals(num(sz.getW()), A4_LARGURA) || !Objects.equals(num(sz.getH()), A4_ALTURA)
                || !Objects.equals(num(m.getTop()), MARGEM_3CM) || !Objects.equals(num(m.getLeft()), MARGEM_3CM)
                || !Objects.equals(num(m.getBottom()), MARGEM_2CM) || !Objects.equals(num(m.getRight()), MARGEM_2CM);

        sz.setW(BigInteger.valueOf(A4_LARGURA));
        sz.setH(BigInteger.valueOf(A4_ALTURA));
        sz.unsetOrient();
        m.setTop(BigInteger.valueOf(MARGEM_3CM));
        m.setLeft(BigInteger.valueOf(MARGEM_3CM));
        m.setBottom(BigInteger.valueOf(MARGEM_2CM));
        m.setRight(BigInteger.valueOf(MARGEM_2CM));
        // header/footer/gutter são atributos obrigatórios do schema: preenche se vierem vazios
        if (m.getHeader() == null) m.setHeader(BigInteger.valueOf(709));
        if (m.getFooter() == null) m.setFooter(BigInteger.valueOf(709));
        if (m.getGutter() == null) m.setGutter(BigInteger.ZERO);

        if (mudou) {
            alteracoes.add(new Alteracao(-1, null, "Papel A4 com margens de 3 cm (superior e esquerda) e 2 cm (inferior e direita)"));
        }
    }

    private static Integer num(Object v) {
        if (v == null) return null;
        try {
            return new BigInteger(v.toString()).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void adicionarNumeroPagina(XWPFDocument doc) {
        if (!doc.getHeaderList().isEmpty()) return; // respeita cabeçalho que o aluno já tem
        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        XWPFParagraph hp = header.createParagraph();
        hp.setAlignment(ParagraphAlignment.RIGHT);
        CTSimpleField campo = hp.getCTP().addNewFldSimple();
        campo.setInstr("PAGE");
        CTR run = campo.addNewR();
        CTRPr rpr = run.addNewRPr();
        rpr.addNewSz().setVal(BigInteger.valueOf(20));
        CTFonts fontes = rpr.addNewRFonts();
        fontes.setAscii(fonte);
        fontes.setHAnsi(fonte);
        run.addNewT().setStringValue("1");
    }

    // ---------------------------------------------------------------- parágrafos

    private List<String> formatarCorpo(XWPFParagraph p) {
        List<String> m = new ArrayList<>();
        alinhar(p, ParagraphAlignment.BOTH, "texto justificado", m);
        recuo(p, 0, RECUO_1_25CM, "recuo de 1,25 cm na primeira linha", m);
        espacamento(p, 1.5, 0, "espaçamento 1,5", m);
        m.addAll(ajustarFonte(p, 12));
        return m;
    }

    private List<String> formatarCitacaoLonga(XWPFParagraph p) {
        List<String> m = new ArrayList<>();
        alinhar(p, ParagraphAlignment.BOTH, "texto justificado", m);
        recuo(p, RECUO_4CM, 0, "citação longa com recuo de 4 cm", m);
        espacamento(p, 1.0, 0, "espaçamento simples na citação longa", m);
        m.addAll(ajustarFonte(p, 10));
        return m;
    }

    private List<String> formatarReferencia(XWPFParagraph p) {
        List<String> m = new ArrayList<>();
        alinhar(p, ParagraphAlignment.LEFT, "referência alinhada à esquerda", m);
        recuo(p, 0, 0, "referência sem recuo", m);
        espacamento(p, 1.0, 240, "espaçamento simples com uma linha entre referências", m);
        m.addAll(ajustarFonte(p, 12));
        return m;
    }

    private List<String> formatarTitulo(XWPFParagraph p, int nivel) {
        List<String> m = new ArrayList<>();
        alinhar(p, ParagraphAlignment.LEFT, "título alinhado à esquerda", m);
        recuo(p, 0, 0, "título sem recuo", m);
        espacamento(p, 1.5, 0, "espaçamento 1,5", m);
        p.setSpacingBefore(0);
        m.addAll(ajustarFonte(p, 12));
        boolean faltavaNegrito = false;
        boolean faltavaCaixaAlta = false;
        for (XWPFRun r : p.getRuns()) {
            if (nivel <= 2 && !r.isBold()) {
                r.setBold(true);
                faltavaNegrito = true;
            }
            if (nivel == 1) {
                String t = r.getText(0);
                if (t != null && !t.equals(t.toUpperCase(Locale.forLanguageTag("pt-BR")))) {
                    r.setText(t.toUpperCase(Locale.forLanguageTag("pt-BR")), 0);
                    faltavaCaixaAlta = true;
                }
            }
        }
        if (faltavaNegrito) m.add("título em negrito");
        if (faltavaCaixaAlta) m.add("seção primária em maiúsculas");
        return m;
    }

    private void alinhar(XWPFParagraph p, ParagraphAlignment alvo, String descricao, List<String> m) {
        if (p.getAlignment() != alvo) m.add(descricao);
        p.setAlignment(alvo);
    }

    private void recuo(XWPFParagraph p, int esquerda, int primeiraLinha, String descricao, List<String> m) {
        int atualEsq = Math.max(0, p.getIndentationLeft());
        int atualPrim = Math.max(0, p.getIndentationFirstLine());
        if (Math.abs(atualEsq - esquerda) > 20 || Math.abs(atualPrim - primeiraLinha) > 20) m.add(descricao);
        p.setIndentationLeft(esquerda);
        p.setIndentationRight(0);
        p.setIndentationHanging(0);
        p.setIndentationFirstLine(primeiraLinha);
    }

    private void espacamento(XWPFParagraph p, double entreLinhas, int depois, String descricao, List<String> m) {
        if (Math.abs(p.getSpacingBetween() - entreLinhas) > 0.01) m.add(descricao);
        p.setSpacingBetween(entreLinhas, LineSpacingRule.AUTO);
        p.setSpacingAfter(depois);
    }

    private List<String> ajustarFonte(XWPFParagraph p, int tamanho) {
        boolean fonteMudou = false;
        boolean tamanhoMudou = false;
        for (XWPFRun r : p.getRuns()) {
            if (!fonte.equalsIgnoreCase(r.getFontFamily())) {
                r.setFontFamily(fonte);
                fonteMudou = true;
            }
            Double atual = r.getFontSizeAsDouble();
            // notas, sobrescritos e similares menores que o corpo continuam como estão
            if (atual == null || Math.abs(atual - tamanho) > 0.1) {
                if (r.getSubscript() == null || r.getSubscript() == VerticalAlign.BASELINE) {
                    r.setFontSize(tamanho);
                    tamanhoMudou = true;
                }
            }
        }
        List<String> m = new ArrayList<>();
        if (fonteMudou) m.add("fonte " + fonte);
        if (tamanhoMudou) m.add("tamanho " + tamanho);
        return m;
    }

    private static void registrar(List<Alteracao> alteracoes, int indice, String texto, List<String> mudancas) {
        if (mudancas.isEmpty()) return;
        String trecho = texto.length() > 120 ? texto.substring(0, 117) + "..." : texto;
        String descricao = String.join("; ", mudancas);
        descricao = Character.toUpperCase(descricao.charAt(0)) + descricao.substring(1);
        alteracoes.add(new Alteracao(indice, trecho, descricao));
    }

    // ---------------------------------------------------------------- correções de texto

    /**
     * Troca o trecho original pelo sugerido quando ele está inteiro dentro de um mesmo
     * "run" do Word (pedaço de texto com a mesma formatação). Quando o trecho atravessa
     * runs, a troca é pulada para não quebrar negrito, itálico ou links.
     *
     * @return {aplicadas, ignoradas}
     */
    private int[] aplicarCorrecoes(XWPFDocument doc, List<Correcao> correcoes, List<Alteracao> alteracoes) {
        List<XWPFParagraph> paragrafos = doc.getParagraphs();
        int aplicadas = 0;
        int ignoradas = 0;
        for (Correcao c : correcoes) {
            if (c.original() == null || c.original().isBlank() || c.sugerido() == null
                    || c.original().equals(c.sugerido())) {
                ignoradas++;
                continue;
            }
            boolean feito = false;
            if (c.paragrafoIndice() != null && c.paragrafoIndice() >= 0 && c.paragrafoIndice() < paragrafos.size()) {
                feito = substituir(paragrafos.get(c.paragrafoIndice()), c);
                if (feito) alteracoes.add(new Alteracao(c.paragrafoIndice(), c.sugerido(),
                        "Correção: \"" + c.original() + "\" → \"" + c.sugerido() + "\""));
            }
            if (!feito) {
                for (int i = 0; i < paragrafos.size() && !feito; i++) {
                    feito = substituir(paragrafos.get(i), c);
                    if (feito) alteracoes.add(new Alteracao(i, c.sugerido(),
                            "Correção: \"" + c.original() + "\" → \"" + c.sugerido() + "\""));
                }
            }
            if (feito) aplicadas++;
            else ignoradas++;
        }
        return new int[]{aplicadas, ignoradas};
    }

    private static boolean substituir(XWPFParagraph p, Correcao c) {
        for (XWPFRun r : p.getRuns()) {
            String t = r.getText(0);
            if (t != null && t.contains(c.original())) {
                r.setText(t.replaceFirst(java.util.regex.Pattern.quote(c.original()),
                        java.util.regex.Matcher.quoteReplacement(c.sugerido())), 0);
                return true;
            }
        }
        return false;
    }
}
