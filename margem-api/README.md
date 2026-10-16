# Margem API

Backend do Margem em Spring Boot 3.5 / Java 21. Recebe o trabalho (.docx ou .pdf), manda o texto
para a OpenAI avaliar, devolve o relatório no formato das telas e gera uma cópia .docx formatada em ABNT.

## Rodar

```bash
export OPENAI_API_KEY=sk-...        # chave da API (platform.openai.com), ver aviso abaixo
mvn spring-boot:run                 # sobe em http://localhost:8080
mvn verify                          # testes (a OpenAI é simulada, não gasta crédito)
```

Produção (PostgreSQL): `SPRING_PROFILES_ACTIVE=prod` com `DATABASE_URL=jdbc:postgresql://...`,
`DATABASE_USER`, `DATABASE_PASSWORD`. Há um `Dockerfile` pronto.

| Variável | Padrão | Para quê |
|---|---|---|
| `OPENAI_API_KEY` | — | obrigatória |
| `OPENAI_MODEL` | `gpt-4.1` | qualquer modelo com Structured Outputs e contexto longo |
| `OPENAI_TIMEOUT` | `PT5M` | tempo máximo por chamada |
| `MARGEM_CORS` | localhost:3000 e a URL da Vercel | origens do front |
| `MARGEM_ARMAZENAMENTO` | `./data/arquivos` | onde ficam os arquivos |
| `MARGEM_MAX_CHARS_PARTE` | `90000` | acima disso o trabalho é analisado em partes |
| `MARGEM_FONTE_ABNT` | `Times New Roman` | ou `Arial` |

### Aviso sobre "Codex pago"

A assinatura do ChatGPT/Codex **não** dá acesso à API. O backend precisa de uma chave criada em
platform.openai.com, com cobrança própria por uso (tokens). Um TCC de 60 páginas fica em torno de
40 a 60 mil tokens de entrada por análise; confira o preço do modelo escolhido na página de preços da OpenAI.

## Como funciona

```
POST arquivo ──► guarda em disco ──► 202 (status RECEBIDO)
                       │
                       ▼ (thread virtual, segundo plano)
     ExtratorTexto: .docx/.pdf → parágrafos com [página, parágrafo]
     + fatos de formatação medidos no .docx (margens, fonte, espaçamento)
                       │
                       ▼
     AnaliseIaService → OpenAI (json_schema estrito) → notas + orientações
     (trabalhos longos: em partes, cada parte recebe o sumário inteiro)
                       │
                       ▼
     status CONCLUIDO ◄── front consulta GET /{id} a cada ~3 s

POST /{id}/formatacao ──► FormatadorAbnt (POI, sem IA) ──► .docx + lista do que mudou
```

Decisões importantes:

- **A IA avalia; a formatação ABNT é feita por código.** Pedir para a IA reescrever o documento inteiro
  perderia imagens, tabelas e estilos, custaria caro e poderia alterar o texto do aluno. O `FormatadorAbnt`
  aplica as regras da NBR 14724 direto no .docx e só troca texto nas correções de português que a IA
  apontou (e só quando a troca é segura).
- **A nota é guardada de 0 a 10.** A escala (I/R/B/MB, 0 a 10, 0 a 100) só muda a exibição, então trocar
  depois não exige nova análise.
- **Página e parágrafo** vêm das marcas de quebra que o Word grava no .docx. Arquivos sem essas marcas
  (ex.: exportados do Google Docs) têm a página estimada por número de caracteres.
- **Prompt e schema** ficam em `src/main/resources/prompts/`, editáveis sem mexer em Java.

## Endpoints

Todos exigem o cabeçalho `X-Cliente-Id` (UUID gerado pelo front e guardado no `localStorage`), que separa
os trabalhos de cada aluno até existir login. Erros vêm em `application/problem+json`; o campo `detail`
é uma frase pronta para a tela.

| Tela | Método | Rota | Corpo |
|---|---|---|---|
| Novo documento → Analisar | `POST` | `/api/documentos` | multipart: `arquivo`, `tipoTrabalho` (`TCC`, `ARTIGO`, `RELATORIO`, `RESENHA`), `curso`, `escalaNota` (`CONCEITO`, `DEZ`, `CEM`) |
| Seus trabalhos | `GET` | `/api/documentos?limite=50` | |
| Relatório (e polling) | `GET` | `/api/documentos/{id}` | |
| Trocar escala | `PATCH` | `/api/documentos/{id}` | `{"escalaNota":"CEM"}` |
| Marcar como resolvido | `PATCH` | `/api/documentos/{id}/orientacoes/{oid}` | `{"resolvida":true}` |
| Tentar de novo | `POST` | `/api/documentos/{id}/reanalise` | |
| Formatar ABNT | `POST` | `/api/documentos/{id}/formatacao` | `{"aplicarCorrecoes":true}` (opcional) |
| Baixar cópia ABNT | `GET` | `/api/documentos/{id}/formatacao/arquivo` | |
| Apagar | `DELETE` | `/api/documentos/{id}` | apaga registro e arquivos |

### Resposta do relatório (resumida)

```json
{
  "id": "…", "nomeArquivo": "tcc.docx", "formato": "DOCX", "status": "CONCLUIDO", "etapa": 3,
  "escalaNota": "DEZ",
  "nota": { "exibicao": "5,8", "valor": 5.8, "maximo": 10, "percentual": 58,
            "faixa": { "codigo": "R", "nome": "Regular",
                       "descricao": "Tem base, mas ainda faltam partes importantes.", "imagem": "regular" } },
  "criterios": [ { "chave": "estrutura", "rotulo": "Estrutura", "exibicao": "6,5", "percentual": 65, "status": "Atenção" } ],
  "contagem": { "total": 5, "criticas": 1, "atencao": 4, "resolvidas": 0 },
  "orientacoes": [ {
      "id": "…", "severidade": "CRITICO", "severidadeRotulo": "Crítico",
      "categoria": "CITACOES_REFERENCIAS", "categoriaRotulo": "Citações e referências",
      "titulo": "Citação direta sem número de página", "localizacao": "Página 14, parágrafo 2",
      "trecho": "Segundo Silva, a metodologia deve ser clara e replicável.",
      "porQueImporta": "A NBR 10520 exige a página em citações diretas…",
      "comoAjustar": "Acrescente a página depois do ano, por exemplo (SILVA, 2020, p. 45)…",
      "correcao": null, "resolvida": false } ],
  "formatacao": { "disponivel": false,
                  "motivo": "Este trabalho foi enviado em PDF. Para formatar em ABNT, envie o arquivo .docx.",
                  "gerada": false, "alteracoes": [] }
}
```

`faixa.imagem` é a chave da imagem de cada faixa no front (`insuficiente`, `regular`, `bom`, `muito-bom`).
`formatacao.alteracoes` lista cada parágrafo que mudou (página, trecho, descrição), para o marca-texto amarelo.

### Chamando do front (Next.js)

```ts
const API = process.env.NEXT_PUBLIC_API_URL!;
const cliente = localStorage.getItem("margem-cliente") ?? crypto.randomUUID();
localStorage.setItem("margem-cliente", cliente);

const form = new FormData();
form.append("arquivo", file);
form.append("tipoTrabalho", "TCC");
form.append("escalaNota", "DEZ");
const doc = await fetch(`${API}/api/documentos`, {
  method: "POST", body: form, headers: { "X-Cliente-Id": cliente },
}).then(r => r.json());

// polling até concluir
let rel = doc;
while (rel.status === "RECEBIDO" || rel.status === "ANALISANDO") {
  await new Promise(r => setTimeout(r, 3000));
  rel = await fetch(`${API}/api/documentos/${doc.id}`, { headers: { "X-Cliente-Id": cliente } }).then(r => r.json());
}
```

## O que o formatador ABNT faz (NBR 14724)

- A4, margens 3 cm (superior/esquerda) e 2 cm (inferior/direita)
- Corpo: fonte 12, espaçamento 1,5, justificado, recuo de 1,25 cm na primeira linha
- Citação longa (parágrafo que já tinha recuo à esquerda): recuo de 4 cm, fonte 10, espaçamento simples
- Títulos (estilos de título do Word): à esquerda, sem recuo, negrito; seção primária em MAIÚSCULAS
- Referências: à esquerda, espaçamento simples, uma linha entre elas
- Número de página no canto superior direito, fonte 10 (se o documento ainda não tiver cabeçalho)
- Parágrafos centralizados (capa, folha de rosto) mantêm o alinhamento
- Aplica as correções de português sugeridas pela IA quando o trecho está inteiro num mesmo trecho de formatação

## Próximos passos sugeridos

1. **Login** (Spring Security + JWT, ou o provedor que o front usar) no lugar do `X-Cliente-Id`.
2. **Limite de uso por aluno** (ex.: N análises por dia) para controlar o gasto com a OpenAI.
3. **Armazenamento em nuvem** (S3/R2) quando houver mais de uma instância; só a classe `Armazenamento` muda.
4. Numeração de página começando na Introdução (exige seções separadas no .docx).
5. Política de retenção (LGPD): apagar arquivos automaticamente após X dias.
