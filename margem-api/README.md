# Margem API

Backend do Margem em Spring Boot 3.5 / Java 21. Recebe o trabalho (.docx ou .pdf), manda o texto
para a OpenAI avaliar, devolve o relatório no formato das telas e gera uma cópia .docx formatada em ABNT.

## Rodar

```bash
export OPENAI_API_KEY=sk-...        # chave da API (platform.openai.com), ver aviso abaixo
export JWT_SECRET="$(openssl rand -base64 48)"   # obrigatório; sem ele a API não sobe
mvn spring-boot:run                 # sobe em http://localhost:8080
mvn verify                          # testes (a OpenAI é simulada, não gasta crédito)
```

Swagger em desenvolvimento: http://localhost:8080/swagger-ui/index.html (faça login em `/api/auth/login`,
copie o `accessToken` e cole no botão **Authorize**).

Se você já rodou uma versão anterior (que usava `X-Cliente-Id`), apague a pasta `data/` antes de subir:
os trabalhos antigos não têm dono e o esquema do banco mudou.

Produção (PostgreSQL): `SPRING_PROFILES_ACTIVE=prod` com `DATABASE_URL=jdbc:postgresql://...?sslmode=require`,
`DATABASE_USER`, `DATABASE_PASSWORD`. Há um `Dockerfile` pronto e um `.env.example` com todas as variáveis.
No perfil `prod` o Swagger fica desligado e o Hibernate roda em `validate`: na primeira subida use
`DDL_AUTO=update` com um usuário que possa criar tabelas, depois volte para `validate` com o usuário
restrito da aplicação (só SELECT/INSERT/UPDATE/DELETE).

| Variável | Padrão | Para quê |
|---|---|---|
| `JWT_SECRET` | sem padrão | obrigatória, mínimo 32 caracteres (`openssl rand -base64 48`) |
| `OPENAI_API_KEY` | — | obrigatória |
| `JWT_EMISSOR` / `JWT_AUDIENCIA` | `margem-api` / `margem-front` | validados em todo token |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | `PT10M` / `P14D` | validade do access token e do cookie de renovação |
| `COOKIE_SECURE` / `COOKIE_SAMESITE` | `true` / `None` | `None` quando front e API estão em sites diferentes; `Lax` se estiverem no mesmo site |
| `FORWARD_HEADERS` | `none` | use `native` atrás de proxy, para o limite de requisições ver o IP real |
| `MARGEM_SWAGGER` | `true` (dev), `false` (prod) | liga/desliga Swagger e `/v3/api-docs` |
| `DDL_AUTO` | `validate` (prod) | ver a seção de produção acima |
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

## Segurança e login

- **Login próprio**: e-mail e senha (BCrypt, custo 12, 10 a 72 caracteres). O `accessToken` (JWT HS256, 10 min) vai no
  corpo e o front o guarda **só em memória**, enviando `Authorization: Bearer ...`. A renovação usa o cookie
  `margem_refresh` (HttpOnly, Secure, SameSite configurável, restrito a `/api/auth`), que gira a cada uso;
  se um refresh token já usado reaparecer, todas as sessões da conta são encerradas.
- **Dono do trabalho** vem do claim `sub` do token, nunca de cabeçalho ou parâmetro. Trabalho de outra pessoa responde
  `404`, igual a um id que não existe.
- **Sem token ou token inválido**: `401`. **Sem permissão** (ex.: `/api/admin/**` sem papel ADMIN): `403`.
- **CSRF**: as rotas de negócio não usam cookie, então não são afetadas. `/api/auth/refresh` e `/api/auth/logout`
  usam cookie e exigem o cabeçalho `X-Margem-Csrf` (qualquer valor), que só as origens do CORS conseguem enviar.
- **Limites** (HTTP 429 com `Retry-After`, configuráveis em `margem.seguranca.limites`): 30 chamadas de auth por IP
  a cada 15 min, 10 tentativas de login por e-mail a cada 15 min, 20 envios/reanálises/formatações por usuário por
  hora, 300 chamadas por usuário por minuto. Os contadores ficam na memória da instância; com mais de uma instância
  troque por Bucket4j com Redis ou JDBC.
- **CORS** só para as origens de `MARGEM_CORS` (sem `*`). Respostas levam CSP, `X-Frame-Options`, HSTS (em HTTPS),
  `Referrer-Policy`, `Cache-Control: no-store`.
- Relatório completo da auditoria: `../SECURITY_AUDIT.md` (na raiz do repositório).

## Endpoints

Todas as rotas `/api/documentos` e `/api/auth/me` exigem `Authorization: Bearer <accessToken>`. Erros vêm em
`application/problem+json`; o campo `detail` é uma frase pronta para a tela.

| Auth | Método | Rota | Corpo |
|---|---|---|---|
| Criar conta (já entra) | `POST` | `/api/auth/cadastro` | `{"email","senha","nome?"}` |
| Entrar | `POST` | `/api/auth/login` | `{"email","senha"}` |
| Renovar | `POST` | `/api/auth/refresh` | cookie + cabeçalho `X-Margem-Csrf: 1` |
| Sair | `POST` | `/api/auth/logout` | cookie + cabeçalho `X-Margem-Csrf: 1` |
| Quem sou eu | `GET` | `/api/auth/me` | |

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
let accessToken = ""; // só em memória: não use localStorage

async function entrar(email: string, senha: string) {
  const r = await fetch(`${API}/api/auth/login`, {
    method: "POST", credentials: "include",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email, senha }),
  });
  accessToken = (await r.json()).accessToken;
}

// ao receber 401, renova uma vez e repete a chamada
async function renovar() {
  const r = await fetch(`${API}/api/auth/refresh`, {
    method: "POST", credentials: "include", headers: { "X-Margem-Csrf": "1" },
  });
  if (!r.ok) throw new Error("sessão expirada");
  accessToken = (await r.json()).accessToken;
}

const form = new FormData();
form.append("arquivo", file);
form.append("tipoTrabalho", "TCC");
form.append("escalaNota", "DEZ");
const doc = await fetch(`${API}/api/documentos`, {
  method: "POST", body: form, headers: { Authorization: `Bearer ${accessToken}` },
}).then(r => r.json());
// polling: GET /api/documentos/{id} a cada ~3 s com o mesmo cabeçalho até status != RECEBIDO/ANALISANDO
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

1. **Migrações de banco** (Flyway) no lugar do `ddl-auto`, para o usuário do banco não precisar de DDL.
2. **Limite de gasto com a OpenAI por aluno** (hoje há só o limite de 20 envios por hora).
3. **Armazenamento em nuvem** (S3/R2) quando houver mais de uma instância; só a classe `Armazenamento` muda.
4. Numeração de página começando na Introdução (exige seções separadas no .docx).
5. Política de retenção (LGPD): apagar arquivos automaticamente após X dias.
