package com.margem.api;

import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class DocxTeste {

    private DocxTeste() {
    }

    public static byte[] tccSimples() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFStyles estilos = doc.createStyles();
            var ctStyle = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle.Factory.newInstance();
            ctStyle.setStyleId("Heading1");
            ctStyle.addNewName().setVal("heading 1");
            estilos.addStyle(new XWPFStyle(ctStyle));

            XWPFParagraph capa = doc.createParagraph();
            capa.setAlignment(ParagraphAlignment.CENTER);
            run(capa, "UNIVERSIDADE EXEMPLO", "Arial", 14);

            XWPFParagraph titulo = doc.createParagraph();
            titulo.setStyle("Heading1");
            titulo.setPageBreak(true);
            run(titulo, "1 Introdução", "Calibri", 16);

            XWPFParagraph corpo = doc.createParagraph();
            corpo.setAlignment(ParagraphAlignment.LEFT);
            run(corpo, "Este trabalho analiza o impacto da metodologia ágil em equipes pequenas de desenvolvimento "
                    + "de software, considerando a literatura recente e dados coletados em três empresas.", "Calibri", 11);

            XWPFParagraph citacao = doc.createParagraph();
            citacao.setIndentationLeft(1300);
            run(citacao, "Segundo Silva, a metodologia deve ser clara e replicável, de modo que outro pesquisador "
                    + "consiga chegar aos mesmos resultados seguindo os mesmos passos descritos no trabalho.", "Calibri", 11);

            XWPFParagraph refs = doc.createParagraph();
            refs.setStyle("Heading1");
            run(refs, "REFERÊNCIAS", "Calibri", 16);

            XWPFParagraph ref = doc.createParagraph();
            ref.setAlignment(ParagraphAlignment.BOTH);
            ref.setIndentationFirstLine(709);
            run(ref, "SILVA, João. Metodologia científica. São Paulo: Atlas, 2020.", "Calibri", 11);

            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void run(XWPFParagraph p, String texto, String fonte, int tamanho) {
        XWPFRun r = p.createRun();
        r.setText(texto);
        r.setFontFamily(fonte);
        r.setFontSize(tamanho);
    }
}
