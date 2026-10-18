# SECURITY_AUDIT.md

Auditoria de segurança da `margem-api` (Java 21, Spring Boot 3.5, Maven, JPA, H2 em dev e PostgreSQL em produção).
Data: 2026-10-18. Caminhos relativos à raiz do repositório. Os números de linha foram lidos dos arquivos reais depois das alterações; as "linhas originais" vêm do commit `0fb4769`.

## Resumo

Estado inicial: a API não tinha autenticação. A identidade do aluno era o cabeçalho `X-Cliente-Id`, escolhido pelo próprio cliente. Quem conhecesse (ou adivinhasse) o valor lia, alterava, apagava e baixava os trabalhos dessa pessoa. A API também gastava créditos da OpenAI sem limite para qualquer chamador anônimo, o CORS aceitava qualquer cabeçalho, o banco H2 de dev abria uma porta de rede (`AUTO_SERVER`) e as dependências estavam com dezenas de CVEs publicados.

Decisão de arquitetura (confirmada com o responsável): **login próprio na API**, com JWT de acesso curto e refresh token rotativo em cookie HttpOnly. Não se usou provedor OIDC porque o projeto não tem nenhum.

| Severidade | Achados | Corrigidos | Pendentes |
|---|---|---|---|
| Crítica | 3 | 3 | 0 |
| Alta | 5 | 5 | 0 |
| Média | 5 | 5 (um parcial, ver SEC-009) | 0 |
| Baixa | 6 | 3 (2 não aplicáveis) | 1 |

Resultado final: **Necessita de correções antes do deploy** (ver "Pendências": migrações de banco, contadores de limite compartilhados e rotação do segredo no ambiente real). Nada impede um deploy de homologação.

---

## Achados e alterações

### SEC-001: Autenticação inexistente, identidade vinda do cliente
Severidade: CRÍTICA. OWASP: A07 Identification and Authentication Failures / A01 Broken Access Control.

Arquivo: `margem-api/src/main/java/com/margem/api/web/DocumentoController.java`
Linhas originais: 31 (`CLIENTE = "X-Cliente-Id"`), 42, 55 e demais `@RequestHeader(CLIENTE)`, 116-120 (`validarCliente`).
Código original: `validarCliente` só conferia que o cabeçalho casava com `[A-Za-z0-9-]{8,100}`. Qualquer valor nesse formato virava "o dono".
Risco: não existia autenticação. O valor funcionava como senha previsível e visível em logs, proxies e histórico do navegador.
Correção: todas as rotas de negócio passaram a exigir `Authorization: Bearer <JWT>`. O dono é o claim `sub` do token já validado (`dono(Authentication)`).
Linhas finais: `DocumentoController.java` 133-135 (`dono`), usos nas linhas 55, 66, 73, 81, 90, 97, 108, 115 e 129. Novos arquivos: `seguranca/SecurityConfig.java` 1-119, `seguranca/JwtService.java` 1-69, `seguranca/AuthService.java` 1-152, `web/AuthController.java` 1-112, `dominio/Usuario.java`, `dominio/RefreshToken.java`, `repositorio/UsuarioRepository.java`, `repositorio/RefreshTokenRepository.java` (todos sob `margem-api/src/main/java/com/margem/api/`).
Testes: `AuthTest.rotasPrivadasSemTokenDao401` (AuthTest.java:41), `SegurancaTest.identificadorDoDonoEnviadoPeloClienteEIgnorado` (SegurancaTest.java:89).
Status: CORRIGIDO.

### SEC-002: IDOR/BOLA por identidade forjável
Severidade: CRÍTICA. OWASP: A01 Broken Access Control.

Arquivos: `margem-api/src/main/java/com/margem/api/dominio/Documento.java` (linhas originais 21-22, `String clienteId`), `repositorio/DocumentoRepository.java` (15-20), `servico/DocumentoService.java`.
Problema: o isolamento dependia só do texto do cabeçalho. Trocando o valor, um usuário acessava todos os trabalhos de outro.
Correção: o campo virou `UUID usuarioId`, preenchido a partir do token. Toda consulta é feita por `(id, usuarioId)` na camada de serviço (`buscar` e `buscarCompleto`); não há rota que busque só por `id`. Trabalho de outra pessoa responde 404, indistinguível de id inexistente, para não permitir enumeração.
Linhas finais: `Documento.java` 21-22, 76-78, 131; `DocumentoRepository.java` 15-20; `DocumentoService.java` 182-190.
Testes: `SegurancaTest.outroUsuarioNaoAcessaNenhumaRotaDoDocumentoDaAna` (54), `respostaDeOutroUsuarioNaoDiferenciaDeIdInexistente` (76), `tokenValidoDeUsuarioQueNaoExisteNaoAcessaNada` (98); `FluxoDocumentoTest` (outro aluno recebe 404).
Status: CORRIGIDO.

### SEC-003: Dependências com CVEs críticos e altos
Severidade: CRÍTICA. OWASP: A06 Vulnerable and Outdated Components.

Arquivo: `margem-api/pom.xml`
Linha original: 10 (`spring-boot-starter-parent 3.5.6`), 20-21 (POI 5.4.1, PDFBox 3.0.5).
Problema: consulta ao OSV.dev (ver "Scanners") apontou 78 avisos em 19 artefatos, incluindo Tomcat 10.1.46 (17 avisos), Spring MVC 6.2.11, Jackson 2.19.2, Logback 1.5.18 e Spring Security 6.5.5.
Correção: parent para 3.5.16, POI 5.5.1, PDFBox 3.0.8, springdoc 2.8.17, e sobreposição de versões acima do que o Boot gerencia para Jackson (2.21.8), Tomcat (10.1.61), PostgreSQL JDBC (42.7.14), commons-lang3 (3.20.0) e Log4j API (2.25.5).
Linhas finais: `pom.xml` 10, 22-32.
Teste: build completo (`mvn clean verify`, 45 testes) e nova consulta OSV, que ficou só com 2 avisos em `spring-webmvc` (ver PEN-005).
Status: CORRIGIDO (resíduo documentado em PEN-005).

### SEC-004: Sem limite de requisições (força bruta e gasto com OpenAI)
Severidade: ALTA. OWASP: A04 Insecure Design / A07.

Arquivo: `margem-api/src/main/java/com/margem/api/web/DocumentoController.java` (rotas de envio, reanálise e formatação) e ausência de qualquer filtro.
Problema: qualquer chamador podia enviar arquivos de até 20 MB em loop, cada um disparando uma chamada paga à OpenAI, e tentar logins sem freio.
Correção: Bucket4j com `Caffeine` (contadores com expiração). `FiltroLimiteRequisicoes` limita por IP as rotas `/api/auth/*` (30 por 15 min) e por usuário as demais: envio, reanálise e formatação 20 por hora, resto 300 por minuto. Login ainda tem teto por e-mail, existindo a conta ou não (10 por 15 min), em `AuthService.entrar`. Respostas 429 com `Retry-After`. Valores em `application.yml:71-76`, sobrescrevíveis por propriedade. O IP vem de `getRemoteAddr()`; `X-Forwarded-For` só vale atrás de proxy confiável via `FORWARD_HEADERS=native` (`application.yml:27`).
Linhas finais: `seguranca/LimitadorRequisicoes.java` 1-35; `seguranca/FiltroLimiteRequisicoes.java` 40-72; `seguranca/AuthService.java` 72-74; `config/SegurancaProperties.java` 1-33; `seguranca/SecurityConfig.java` 85-88.
Testes: `LimiteRequisicoesTest` (36, 61, 79: IP com `X-Forwarded-For` rotativo, envio por usuário isolado entre usuários, teto geral), `AuthTest.loginTemLimitePorEmail` (234).
Status: CORRIGIDO. Limitação: contadores em memória (PEN-002).

### SEC-005: Sem refresh seguro nem logout
Severidade: ALTA. OWASP: A07.

Problema/escopo: ao introduzir login era preciso evitar tokens de longa duração no `localStorage`.
Correção: access token JWT HS256 de 10 min (`JwtService.emitir`, linhas 48-61), validado por assinatura, emissor, audiência e expiração (linhas 38-41, 63-69). O refresh token tem 256 bits aleatórios, é guardado só como SHA-256, vai em cookie HttpOnly/Secure/SameSite com `Path=/api/auth`, gira a cada uso, e a reapresentação de um token já usado revoga todas as sessões da conta (`AuthService.renovar`, 84-98, com `noRollbackFor` para a revogação persistir). Logout revoga o token e apaga o cookie (`AuthService.sair`, 100-104; `AuthController.sair`, 71-79). `refresh` e `logout` exigem o cabeçalho `X-Margem-Csrf` (`AuthController.java` 35, 105-107). Senhas com BCrypt custo 12 (`AuthService.java` 42), com senha falsa para igualar o tempo de login de e-mail inexistente (45, 77), mensagem única de erro e limite de 72 bytes (146-151) para o BCrypt não truncar em silêncio.
Linhas finais: ver arquivos citados.
Testes: `AuthTest` 52 (assinatura, expiração, emissor, audiência, alg=none), 73 (token em query string), 124 (resposta igual para senha errada e e-mail inexistente), 144 (hash BCrypt no banco), 153 (flags do cookie), 167 (rotação e reuso), 186 (CSRF), 196 (logout).
Status: CORRIGIDO. O access token já emitido não é revogável antes de expirar (10 min), ver PEN-004.

### SEC-006: Elevação de privilégio (papel vindo do cliente)
Severidade: ALTA. OWASP: A01.

Arquivo: `margem-api/src/main/java/com/margem/api/seguranca/SecurityConfig.java`
Problema potencial: ao criar contas, aceitar `papel` ou `isAdmin` do corpo permitiria virar ADMIN.
Correção: `Requisicoes.Cadastro` só tem `email`, `senha` e `nome`; o papel nasce `USER` no banco (`Usuario.java` 28-29) e vai no token assinado. A política `/api/admin/**` exige `ROLE_ADMIN` (linha 72) e o resto da API é `denyAll` por padrão (linha 74).
Testes: `AuthTest.usuarioComumNaoAcessaAreaAdministrativa` (79: 403), `papelNaoVemDoClienteNemDeJwtForjado` (87: propriedades extras ignoradas, token forjado dá 401).
Status: CORRIGIDO. Observação: hoje não há rota `/api/admin/**`; a regra só garante que qualquer rota futura nasça protegida.

### SEC-007: CORS aceitando qualquer cabeçalho, sem política de segurança HTTP
Severidade: ALTA. OWASP: A05 Security Misconfiguration.

Arquivo original: `margem-api/src/main/java/com/margem/api/config/WebConfig.java`, linhas 16-23 (`allowedHeaders("*")`), arquivo removido.
Problema: CORS só no MVC (não cobre erros de filtros), sem credenciais controladas, sem headers de segurança além dos padrões.
Correção: `CorsConfigurationSource` único no Spring Security, só origens exatas de `MARGEM_CORS` (a aplicação não sobe se houver `*`), cabeçalhos permitidos explícitos (`Authorization`, `Content-Type`, `Accept`, `X-Margem-Csrf`). Headers: CSP `default-src 'none'; frame-ancestors 'none'` (fora do Swagger UI), `X-Frame-Options: DENY`, HSTS 1 ano, `Referrer-Policy: no-referrer`, `Permissions-Policy`, `Cache-Control: no-store`, `nosniff`. CSRF desligado de propósito: rotas de negócio usam Bearer, sem cookie, e as duas rotas com cookie exigem cabeçalho customizado.
Linhas finais: `seguranca/SecurityConfig.java` 52-90 e 101-118.
Testes: `SegurancaTest.respostasTemCabecalhosDeSeguranca` (211), `corsSoLiberaAsOrigensConfiguradas` (237).
Status: CORRIGIDO.

### SEC-008: Banco H2 de desenvolvimento abrindo porta TCP
Severidade: ALTA. OWASP: A05.

Arquivo: `margem-api/src/main/resources/application.yml`
Linha original: 12 (`...;MODE=PostgreSQL;AUTO_SERVER=TRUE`).
Problema: `AUTO_SERVER=TRUE` faz o H2 escutar numa porta de rede, com usuário `sa` sem senha. Quem estivesse na mesma rede lia o banco inteiro (trabalhos acadêmicos e, agora, contas).
Correção: removido `AUTO_SERVER`; comentário explicando. Console H2 continua desligado (mantido) e `/h2-console` é negado pela política.
Linhas finais: `application.yml` 10-14.
Teste: `SegurancaTest.soAsRotasPublicasPrevistasSaoPublicas` (251) cobre `/h2-console`.
Status: CORRIGIDO. Em dev, duas JVMs não abrem o mesmo arquivo ao mesmo tempo (efeito esperado).

### SEC-009: Hibernate `ddl-auto: update` em produção
Severidade: MÉDIA. OWASP: A05.

Arquivo: `margem-api/src/main/resources/application.yml`, linha original 17 (valia também para `prod`).
Problema: exigia que o usuário do banco tivesse permissão de DDL em produção, violando o menor privilégio e permitindo alterações de esquema pela aplicação.
Correção: perfil `prod` usa `validate` por padrão (`DDL_AUTO` para a criação inicial). `README.md` explica o procedimento e o usuário restrito.
Linhas finais: `application.yml` 93-97.
Teste: não automatizável sem PostgreSQL. Verificado só que o perfil `prod` sobe com `DDL_AUTO=update` em H2 em memória.
Status: CORRIGIDO parcialmente (falta Flyway, PEN-001).

### SEC-010: Segredo de assinatura sem valor padrão e falha segura
Severidade: MÉDIA. OWASP: A02 Cryptographic Failures.

Arquivos: `application.yml` 62 (`jwt-secret: ${JWT_SECRET}`), `JwtService.java` 28-36.
Problema potencial: um segredo padrão no código permitiria forjar tokens de qualquer instância.
Correção: sem default. Variável ausente ou com menos de 32 caracteres impede a inicialização (testado subindo o jar sem `JWT_SECRET`: `IllegalStateException`). Segredo de teste fica só em `src/test/resources/application-test.yml`.
Teste: execução manual do jar sem a variável (não há teste automatizado para falha de inicialização).
Status: CORRIGIDO.

### SEC-011: Respostas de erro e tratamento centralizado
Severidade: MÉDIA. OWASP: A05 / A09.

Arquivo: `margem-api/src/main/java/com/margem/api/web/TratadorErros.java`
Problema: não havia rede de segurança para exceções inesperadas, e erros de validação não traziam mensagens úteis. Dependia do tratamento padrão do Spring.
Correção: handler final para `Exception` que mantém o status das exceções do próprio Spring MVC (`ErrorResponse`) e devolve 500 genérico, sem stack trace nem mensagem interna; novos handlers para 401, 403, 429 (`Retry-After`) e validação de parâmetros; mensagens de Bean Validation só com texto escrito por nós. `server.error.*` com `never` (`application.yml:28-32`).
Linhas finais: `TratadorErros.java` 59-87, 93-100 e 111-124; `Erros.java` 28-51.
Testes: `SegurancaTest.errosNaoVazamDetalhesInternos` (225), `AuthTest.cadastroInvalidoDa400` (208), `SegurancaTest.parametroLimiteForaDaFaixaDa400` (125).
Status: CORRIGIDO.

### SEC-012: Validação de entrada incompleta
Severidade: MÉDIA. OWASP: A03 Injection / A04.

Arquivos: `web/DocumentoController.java`, `web/dto/Requisicoes.java`.
Problema: `limite` aceitava qualquer inteiro (limitado só em runtime), `curso` era truncado em silêncio, DTOs de auth não existiam.
Correção: `@Validated` no controller e `@Min(1) @Max(100)` em `limite` (36, 65); `@Size(max = 150)` em `curso`; DTOs de login e cadastro com `@NotBlank`, `@Email`, `@Size` (`Requisicoes.java` 27-47). Todas as consultas usam Spring Data/JPQL parametrizado (nenhuma concatenação de SQL na base de código, verificado por busca). Mass assignment é inexistente porque os corpos são `record`s com poucos campos, nunca entidades.
Testes: `SegurancaTest` 117 (ids malformados e SQLi em id), 125, 135, 148 (propriedades extras), 160 (SQLi em `curso`), 171; `AuthTest` 227 (SQLi no login).
Status: CORRIGIDO.

### SEC-013: Extração de .docx/.pdf sem teto de expansão
Severidade: MÉDIA. OWASP: A05 / A04 (DoS).

Arquivo: `margem-api/src/main/java/com/margem/api/servico/extracao/ExtratorTexto.java`
Problema: um .docx pequeno pode expandir para gigabytes ao abrir (zip bomb) e um PDF com milhares de páginas consome CPU/memória no processamento em segundo plano.
Correção: `ZipSecureFile` com razão mínima de inflação 0,01, entrada máxima de 150 MB e texto máximo de 30 MB (linhas 41-46); PDF com no máximo 800 páginas (38, 179). O upload já tinha tamanho máximo (20 MB), verificação de assinatura real do arquivo e nome sanitizado; os arquivos são gravados como `<uuid>-original.<ext>` fora de qualquer pasta servida.
Testes: `SegurancaTest.nomeDeArquivoComTraversalEReduzidoAoNomeBase` (180), `uploadsInvalidosSaoRecusados` (192). Não há teste automatizado de zip bomb real nem de PDF com mais de 800 páginas.
Status: CORRIGIDO (limites sem teste dedicado).

### SEC-014: Swagger/OpenAPI ausente e política de acesso
Severidade: BAIXA. OWASP: A05 (exposição de documentação).

Arquivos: `config/OpenApiConfig.java` 1-66, `application.yml` 33-39 e 99-103, `SecurityConfig.java` 63-75.
Entrega: springdoc 2.8.17 com título, descrição, esquema Bearer (botão Authorize), `@Operation` em todos os endpoints, `@SecurityRequirements` vazio nas rotas públicas e respostas 400/401/403/404/409/429/500 em problem+json em todas as operações. Ligado em dev, desligado no perfil `prod` (e permissão `permitAll` só existe quando ligado). Nenhum exemplo traz credenciais. `persist-authorization: false`.
Testes: `SwaggerTest.Ligado` (27, 44, 51) e `SwaggerTest.Desligado` (66: 401 mesmo com token).
Status: CORRIGIDO.

### SEC-015: Actuator
Severidade: BAIXA. OWASP: A05.

Arquivo: `application.yml` 42-49. O projeto já expunha só `health`; foi acrescentado `show-details: never` e a política de acesso só libera `GET /actuator/health` (`SecurityConfig.java` 67). Teste: `SegurancaTest.soAsRotasPublicasPrevistasSaoPublicas` (251: `/actuator/env`, `/beans`, `/heapdump` dão 401).
Status: CORRIGIDO.

### SEC-016: `.gitignore` e exemplo de variáveis
Severidade: BAIXA. OWASP: A02 / A05.

Arquivos: `margem-api/.gitignore` (original só cobria `.env`), `.gitignore` (novo, raiz), `margem-api/.env.example` (novo, só placeholders).
Correção: ignora `.env.*` (exceto `.env.example`), `*.pem`, `*.key`, `*.p12`, `*.jks`, logs e relatórios de scanner. A raiz do repositório não tinha `.gitignore`: a pasta `data/` (banco H2 com dados reais de trabalhos) estava como não rastreada e podia ser comitada por engano.
Status: CORRIGIDO. Observação: `.idea/` já está versionado; o `.gitignore` não remove o que já foi commitado (PEN-006).

### SEC-017: Segredos no código e no histórico Git
Severidade: BAIXA (nenhum achado). OWASP: A02.

Resultado: Gitleaks 8.30.1 no histórico (3 commits) e na árvore de trabalho: nenhum vazamento. Busca manual por `sk-`, `password`, `secret`, `token` em todos os commits: só nomes de variáveis e texto de README. A chave da OpenAI e as credenciais do banco vêm de variáveis de ambiente (blocos `openai` e `prod` de `application.yml`). O front não recebe nenhuma chave; este repositório não contém front.
Status: NÃO APLICÁVEL (sem segredo a rotacionar). Rotação do `JWT_SECRET` e da chave OpenAI continua sendo responsabilidade do ambiente real.

### SEC-018: Enumeração de contas no cadastro
Severidade: BAIXA. OWASP: A07.

Arquivo: `seguranca/AuthService.java` 61 e 66. Cadastro com e-mail existente responde 409, o que revela que o e-mail tem conta. Mitigado pelo limite por IP e pela mensagem genérica, mas não eliminado (eliminar exigiria confirmação por e-mail, que o projeto não tem).
Teste: `AuthTest.emailDuplicadoDa409` (136).
Status: PENDENTE (aceito, ver PEN-003).

### SEC-019: Conteúdo do aluno enviado à OpenAI e logs
Severidade: BAIXA. OWASP: A09 / A04.

Arquivos: `servico/ia/OpenAiClient.java` 73-74, `servico/AnaliseWorker.java` 66-68. Os logs registram id do documento, contagens e (em erro da OpenAI) um resumo do corpo de erro, nunca o texto do trabalho nem tokens. Instrução maliciosa dentro do trabalho (prompt injection) só consegue alterar o relatório do próprio autor, porque a correção sugerida só é aplicada na cópia dele e só quando o trecho original existe no texto. Nenhuma mudança de código.
Status: NÃO APLICÁVEL como falha; ver PEN-007 sobre LGPD/retenção.

---

## Inventário de endpoints

Todas as respostas de erro são `application/problem+json`. "Dono" significa que a consulta é sempre por `(id, usuarioId)` e responde 404 para trabalho alheio.

| Rota | Método | Autenticação | Autorização | Resultado da revisão |
|---|---|---|---|---|
| `/api/auth/cadastro` | POST | pública | papel sempre USER | OK. Validação, BCrypt, limite por IP. 409 em e-mail repetido (SEC-018) |
| `/api/auth/login` | POST | pública | n/a | OK. Limite por IP e por e-mail, resposta igual para falha de senha e e-mail inexistente |
| `/api/auth/refresh` | POST | cookie HttpOnly | `X-Margem-Csrf` | OK. Rotação e detecção de reuso |
| `/api/auth/logout` | POST | cookie HttpOnly | `X-Margem-Csrf` | OK. Revoga e apaga cookie |
| `/api/auth/me` | GET | Bearer | qualquer usuário | OK. Devolve id, e-mail, nome |
| `/api/documentos` | POST | Bearer | cria no nome do token | OK. Upload validado, limite 20/h |
| `/api/documentos` | GET | Bearer | só os do dono | OK. `limite` 1 a 100 |
| `/api/documentos/{id}` | GET | Bearer | dono | OK. UUID validado; 404 se alheio |
| `/api/documentos/{id}` | PATCH | Bearer | dono | OK. Só `escalaNota` |
| `/api/documentos/{id}` | DELETE | Bearer | dono | OK. Apaga registro e arquivos |
| `/api/documentos/{id}/orientacoes/{oid}` | PATCH | Bearer | dono (via documento) | OK. Orientação só é buscada dentro do documento do dono |
| `/api/documentos/{id}/reanalise` | POST | Bearer | dono | OK. Limite 20/h |
| `/api/documentos/{id}/formatacao` | POST | Bearer | dono | OK. Limite 20/h |
| `/api/documentos/{id}/formatacao/arquivo` | GET | Bearer | dono | OK. Caminho vem do banco, não do cliente |
| `/api/admin/**` | qualquer | Bearer | `ROLE_ADMIN` | Sem rotas hoje; negado para USER (403) |
| `/actuator/health` | GET | pública | n/a | OK. Sem detalhes |
| `/v3/api-docs`, `/swagger-ui/**` | GET | pública em dev | n/a | Inexistente (401) no perfil prod |
| qualquer outra | qualquer | n/a | `denyAll` | OK |

---

## Arquivos criados e alterados

Principais (todos sob `margem-api/`):

| Arquivo | Linhas relevantes | Finalidade |
|---|---|---|
| `pom.xml` | 10, 22-32, 53-80, 111-115 | Versões atualizadas; Security, resource-server, Bucket4j, Caffeine, springdoc, spring-security-test |
| `src/main/resources/application.yml` | 10-14, 22-39, 42-49, 58-76, 88-103 | H2 sem rede, erros sem detalhes, Swagger, segurança, limites, perfil prod |
| `src/main/java/com/margem/api/seguranca/SecurityConfig.java` (novo) | 1-119 | Cadeia de filtros, política de acesso, headers, CORS |
| `.../seguranca/JwtService.java` (novo) | 1-69 | Emissão e validação de JWT |
| `.../seguranca/AuthService.java` (novo) | 1-152 | Cadastro, login, refresh rotativo, logout |
| `.../seguranca/FiltroLimiteRequisicoes.java`, `LimitadorRequisicoes.java` (novos) | 1-72, 1-35 | Rate limiting |
| `.../seguranca/RespostasSeguranca.java` (novo) | 1-52 | 401, 403 e 429 em problem+json |
| `.../web/AuthController.java` (novo) | 1-112 | Rotas de autenticação |
| `.../web/DocumentoController.java` | 25-36, 49-135 | Dono vem do token, validação de parâmetros, Swagger |
| `.../web/TratadorErros.java` | 59-124 | Novos handlers e rede de segurança |
| `.../web/dto/Requisicoes.java`, `Respostas.java` | 27-47; fim do arquivo | DTOs validados de auth |
| `.../dominio/Usuario.java`, `RefreshToken.java`, `Documento.java` | 1-47, 1-43, 21-22 | Entidades de conta e sessão; dono UUID |
| `.../repositorio/UsuarioRepository.java`, `RefreshTokenRepository.java`, `DocumentoRepository.java` | todos | Consultas por dono |
| `.../servico/DocumentoService.java` | 53-190 | Troca de `clienteId` por `usuarioId` |
| `.../servico/Erros.java` | 28-50 | Erros 401, 403, 429 |
| `.../servico/extracao/ExtratorTexto.java` | 35-46, 179 | Limites anti zip bomb e PDF |
| `.../config/SegurancaProperties.java`, `OpenApiConfig.java` (novos) | 1-33, 1-66 | Configuração e Swagger |
| `.../config/WebConfig.java` | removido | Substituído pelo CORS do Security |
| `.gitignore` (raiz, novo), `margem-api/.gitignore`, `margem-api/.env.example` (novo) | todas | Segredos fora do Git |
| `margem-api/README.md` | seções Rodar, Segurança, Endpoints, exemplo de front | Documentação |
| `src/test/java/com/margem/api/AuthTest.java`, `SegurancaTest.java`, `LimiteRequisicoesTest.java`, `SwaggerTest.java`, `Sessoes.java` (novos); `FluxoDocumentoTest.java` (alterado) | ver Testes | Testes de segurança |
| `src/test/resources/application-test.yml` | 7-17 | Segredo e limites de teste |

---

## Scanners

| Ferramenta | Comando | Resultado | Limitações |
|---|---|---|---|
| Gitleaks 8.30.1 | `gitleaks git --redact -v .` e `gitleaks dir --redact margem-api` | 3 commits, nenhum vazamento | Rodou o binário oficial baixado para uma pasta temporária |
| Opengrep 1.31.0 | `opengrep scan --config rules/java --config rules/generic --config rules/dockerfile margem-api/src margem-api/Dockerfile` (regras do repositório `opengrep/opengrep-rules`) | 2 achados informativos: imagens base do `Dockerfile` sem digest (PEN-008). Nenhum achado em código Java | Conjunto de regras Java e genéricas da comunidade; não substitui revisão manual |
| Consulta OSV.dev (equivalente ao Dependency-Check) | `mvn dependency:list -DincludeScope=runtime` + `POST api.osv.dev/v1/querybatch` | Antes: 78 avisos em 19 artefatos. Depois da atualização: 2 avisos em `spring-webmvc 6.2.19` (PEN-005) | Cobre só dependências de runtime diretas e transitivas; avisos não são confirmação de exploração |
| OWASP Dependency-Check 12.1.3 | `mvn org.owasp:dependency-check-maven:12.1.3:check` | NÃO CONCLUÍDO: sem chave da NVD, o download inicial de 409 mil registros levaria horas; interrompido a 7% | Rode com `-DnvdApiKey=...` |
| OWASP ZAP | n/a | NÃO EXECUTADO: não instalado, e o Docker Desktop não estava ativo | Recomenda-se baseline passivo em homologação |
| Bandit | n/a | NÃO APLICÁVEL: não há código Python | n/a |

Execução ao vivo (jar real, H2 em memória): sem token 401; cadastro 201 com cookie `Secure; HttpOnly; SameSite=None`; Swagger e `/v3/api-docs` 200 em dev; `/actuator/env` 401; com `SPRING_PROFILES_ACTIVE=prod` Swagger e `/v3/api-docs` 401 e `/actuator/health` 200; sem `JWT_SECRET` a aplicação não sobe.

## Testes

Comando: `mvn -B clean verify` (Java 24 na máquina, alvo 21). Resultado: **45 testes, 0 falhas, 0 erros**, BUILD SUCCESS.

| Classe | Testes | Cobertura relevante |
|---|---|---|
| `AuthTest` | 16 | 401 sem token e com token inválido (chave errada, expirado, emissor, audiência, `alg=none`, query string), 403 para USER em `/api/admin`, papel não vem do cliente, cadastro/login/me, erro idêntico para senha errada e e-mail inexistente, 409, BCrypt, flags do cookie, rotação e reuso de refresh, CSRF de refresh/logout, logout, validação de cadastro, SQLi no login, limite por e-mail |
| `SegurancaTest` | 17 | IDOR em todas as rotas, indistinguibilidade 404, identificador de dono vindo do cliente ignorado, dados internos não vazam, ids malformados e SQLi, `limite` fora da faixa, enum inválido, JSON ruim, propriedades extras, SQLi em `curso`, uploads inválidos e traversal no nome, headers, erros sem vazamento, CORS, rotas públicas |
| `LimiteRequisicoesTest` | 3 | 429 por IP ignorando `X-Forwarded-For`, limite por usuário isolado, teto geral |
| `SwaggerTest` | 4 | Documentação, esquema Bearer, erros documentados, UI sem CSP bloqueando, desligado devolve 401 |
| `FluxoDocumentoTest` | 2 | Fluxo completo (já existia) adaptado a login; 404 para outro usuário |
| `UnidadeTest` | 3 | Já existiam |

Não coberto: concorrência de dois refresh simultâneos com o mesmo token; zip bomb e PDF gigante reais; falha de inicialização por segredo ausente (verificada à mão); limites com Redis; PostgreSQL real (os testes usam H2).

## Pendências

| Id | Item | Risco | Ação |
|---|---|---|---|
| PEN-001 | Sem migrações de banco (Flyway/Liquibase). O perfil `prod` usa `validate`, então a primeira subida precisa de um passo manual (`DDL_AUTO=update` com usuário administrativo, depois `validate` com usuário restrito) | Média | Adotar Flyway e gerar o esquema inicial |
| PEN-002 | Contadores de rate limit em memória. Com mais de uma instância o limite efetivo é multiplicado | Média | Bucket4j com Redis ou JDBC, ou limite no proxy/gateway |
| PEN-003 | Cadastro revela se um e-mail já existe (409) | Baixa | Confirmação de e-mail, quando houver envio de e-mail |
| PEN-004 | Access token não é revogável antes de expirar (10 min); trocar senha ou apagar conta não derruba tokens já emitidos | Baixa | Reduzir TTL ou guardar versão do token no usuário. Não há troca de senha nem exclusão de conta ainda |
| PEN-005 | `spring-webmvc 6.2.19` com GHSA-j9f9-w8pj-32f8 (fragmentos SSE) e GHSA-pc63-qcmh-9cmg (`XsltView`). A correção listada é só da linha 7.0 | Baixa | Não exploráveis aqui (não há SSE com fragmentos nem XSLT). Reavaliar a cada atualização do Boot 3.5.x |
| PEN-006 | `.idea/` já está versionado no Git (`git ls-files`) | Baixa | `git rm -r --cached .idea` quando quiser |
| PEN-007 | Retenção de dados (LGPD): trabalhos ficam até o aluno apagar. Já constava no README | Média | Política de expiração automática |
| PEN-008 | Imagens do `Dockerfile` sem digest fixo | Baixa | Fixar `@sha256` ao publicar |
| PEN-009 | Dependency-Check e ZAP não executados | Média | Rodar na CI e em homologação |
| PEN-010 | Segredos do ambiente real: gerar `JWT_SECRET` novo (≥32 caracteres), definir `MARGEM_CORS` com o domínio final, HTTPS na borda (`FORWARD_HEADERS=native`), banco com `sslmode=require` e usuário sem DDL | Alta (antes do deploy) | Ação manual no host |
| PEN-011 | A pasta `data/` antiga (H2 de dev, esquema com `cliente_id`) precisa ser apagada antes de subir esta versão | Baixa | `rm -rf data/` |

## Resultado final

**Necessita de correções antes do deploy.**

Os problemas críticos e altos (identidade forjável, IDOR, ausência de login, dependências vulneráveis, ausência de limites, porta do H2, CORS e headers) estão corrigidos e cobertos por testes. Os itens que impedem um deploy de produção são operacionais: definir o ambiente real (PEN-010), decidir como criar o esquema do PostgreSQL (PEN-001) e, se houver mais de uma instância, compartilhar os contadores (PEN-002). Compilação e testes passando não provam que a aplicação é segura; recomenda-se ainda um ZAP passivo em homologação e a execução do Dependency-Check com chave da NVD.
