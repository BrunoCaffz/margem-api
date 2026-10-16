package com.margem.api;

import com.margem.api.config.MargemProperties;
import com.margem.api.dominio.EscalaNota;
import com.margem.api.servico.abnt.FormatadorAbnt;
import com.margem.api.servico.extracao.DocumentoExtraido;
import com.margem.api.servico.extracao.ExtratorTexto;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnidadeTest {

    @Test
    void escalasExibemComoNasTelas() {
        assertThat(EscalaNota.DEZ.exibir(5.8)).isEqualTo("5,8");
        assertThat(EscalaNota.CEM.exibir(5.8)).isEqualTo("58");
        assertThat(EscalaNota.CONCEITO.exibir(5.8)).isEqualTo("R");
        assertThat(EscalaNota.Faixa.de(4.9)).isEqualTo(EscalaNota.Faixa.I);
        assertThat(EscalaNota.Faixa.de(7.0)).isEqualTo(EscalaNota.Faixa.B);
        assertThat(EscalaNota.Faixa.de(8.5)).isEqualTo(EscalaNota.Faixa.MB);
        assertThat(EscalaNota.Faixa.R.descricao).isEqualTo("Tem base, mas ainda faltam partes importantes.");
        assertThat(EscalaNota.status(6.5)).isEqualTo("Atenção");
    }

    @Test
    void extratorNumeraPaginasEParagrafos() throws Exception {
        DocumentoExtraido d = new ExtratorTexto().extrairDocx(new ByteArrayInputStream(DocxTeste.tccSimples()));

        assertThat(d.totalPaginas()).isEqualTo(2);
        assertThat(d.paragrafos()).hasSize(6);
        DocumentoExtraido.Paragrafo corpo = d.paragrafos().get(2);
        assertThat(corpo.pagina()).isEqualTo(2);
        assertThat(corpo.posicaoNaPagina()).isEqualTo(2);
        assertThat(d.paragrafos().get(1).titulo()).isTrue();
        assertThat(d.localizarPorTrecho("metodologia deve ser clara")).map(DocumentoExtraido.Paragrafo::indice).contains(3);
        assertThat(d.fatosFormatacao()).contains("Fonte mais usada");
    }

    @Test
    void formatadorAplicaRegrasAbnt() throws Exception {
        FormatadorAbnt f = new FormatadorAbnt(new MargemProperties("target/x", List.of(), 90000, "Times New Roman"));
        FormatadorAbnt.Resultado r = f.formatar(new ByteArrayInputStream(DocxTeste.tccSimples()),
                List.of(new FormatadorAbnt.Correcao(2, "analiza", "analisa")));

        assertThat(r.correcoesAplicadas()).isEqualTo(1);
        assertThat(r.alteracoes()).isNotEmpty();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(r.arquivo()))) {
            var mar = doc.getDocument().getBody().getSectPr().getPgMar();
            assertThat(new BigInteger(mar.getTop().toString()).intValue()).isEqualTo(1701);
            assertThat(new BigInteger(mar.getRight().toString()).intValue()).isEqualTo(1134);

            List<XWPFParagraph> ps = doc.getParagraphs();
            // capa centralizada continua centralizada
            assertThat(ps.get(0).getAlignment()).isEqualTo(ParagraphAlignment.CENTER);
            // título primário em maiúsculas e negrito
            assertThat(ps.get(1).getText()).isEqualTo("1 INTRODUÇÃO");
            assertThat(ps.get(1).getRuns().getFirst().isBold()).isTrue();
            // corpo: justificado, recuo 1,25 cm, 1,5, Times 12, correção aplicada
            XWPFParagraph corpo = ps.get(2);
            assertThat(corpo.getAlignment()).isEqualTo(ParagraphAlignment.BOTH);
            assertThat(corpo.getIndentationFirstLine()).isEqualTo(709);
            assertThat(corpo.getSpacingBetween()).isEqualTo(1.5);
            assertThat(corpo.getRuns().getFirst().getFontFamily()).isEqualTo("Times New Roman");
            assertThat(corpo.getRuns().getFirst().getFontSizeAsDouble()).isEqualTo(12.0);
            assertThat(corpo.getText()).contains("analisa").doesNotContain("analiza");
            // citação longa: recuo 4 cm, fonte 10
            assertThat(ps.get(3).getIndentationLeft()).isEqualTo(2268);
            assertThat(ps.get(3).getRuns().getFirst().getFontSizeAsDouble()).isEqualTo(10.0);
            // referência: à esquerda, sem recuo
            assertThat(ps.get(5).getAlignment()).isEqualTo(ParagraphAlignment.LEFT);
            assertThat(ps.get(5).getIndentationFirstLine()).isZero();
            // número de página no cabeçalho
            assertThat(doc.getHeaderList()).hasSize(1);
        }
    }
}
