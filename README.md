# Server - GeoRural DataHub
API REST feita em Java usando o Spring Boot Framework para o **GeoRural DataHub**, plataforma de governança e rastreabilidade de dados ambientais de imóveis rurais do Paraná (projeto Fatec para a Visiona — Tecnologia Espacial, equipe [AtlazDB](https://github.com/AtlazDB)).

O front-end está em [Atlaz4-FrontEnd](https://github.com/AtlazDB/Atlaz4-FrontEnd).

**Stack:** Java 17 · Spring Boot 4 · Spring Data JPA + Hibernate Spatial · Flyway · Oracle (dados espaciais em `SDO_GEOMETRY`) · JTS · GeoTools · OCI Object Storage

# 🛠️ Pré-requisitos para rodar o projeto
- **JDK 17+** - [Download](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
- **Docker Desktop** - [Download](https://www.docker.com/get-started/)
- **Git** - [Download](https://git-scm.com/downloads)
- **Credencial da Oracle Cloud (OCI)** com acesso ao bucket do projeto - [Como configurar](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/sdkconfig.htm)

# 🚀 Passos para executar a aplicação
<ol>

<li> <strong> Clone o repositório e navegue até o diretório do projeto </strong> </li>

```bash
git clone https://github.com/AtlazDB/Atlaz4-BackEnd.git
cd Atlaz4-BackEnd
```

<li> <strong> Crie o arquivo <code>.env</code> na raiz, a partir do <code>.env.example</code> </strong> </li>

| Windows     | `copy .env.example .env` |
| ----------- | ------------------------ |
| **Linux/MacOS** | **`cp .env.example .env`** |

Preencha as variáveis:

| Variável | O que é |
| -------- | ------- |
| `OCI_NAMESPACE` | Namespace do Object Storage na OCI |
| `OCI_BUCKET` | Nome do bucket onde ficam as zonas do data lake |
| `DB_USERNAME` | Usuário do banco. O `docker-compose` cria esse usuário no Oracle local |
| `DB_PASSWORD` | Senha do banco (também vira a senha do administrador do Oracle local) |

Opcionais: `OCI_ZONA_BRUTA` (padrão `bruta`) e `APP_CORS_ORIGENS` (padrão: qualquer porta de `localhost`).

<li> <strong> Configure a credencial da OCI </strong> </li>

A aplicação lê o arquivo `~/.oci/config`, no perfil **`DEFAULT`**, para acessar o Object Storage.

> [!IMPORTANT]
> Sem o `~/.oci/config` a aplicação **não inicia**, mesmo que você não vá fazer upload de arquivos.

<li> <strong> Suba o banco de dados Oracle </strong> </li>

```bash
docker compose up -d
```

> [!IMPORTANT]
> O Spring **não** sobe o container sozinho: rode o comando acima antes da aplicação, com o **Docker Desktop** aberto. A primeira subida baixa a imagem e cria o banco, e pode levar alguns minutos. O banco está pronto quando `docker logs oracle` mostrar `DATABASE IS READY TO USE!`.

<li> <strong> Execute o projeto conforme seu sistema </strong> </li>

| Windows     | `.\mvnw spring-boot:run`                  |
| ----------- | ----------------------------------------- |
| **Linux/MacOS** | **`chmod +x mvnw && ./mvnw spring-boot:run`** |

O Flyway cria as tabelas sozinho na primeira subida. A API fica disponível em `http://localhost:8080/api/v1`.

</ol>

# 📡 Principais rotas
Base: `http://localhost:8080/api/v1`. Os erros seguem o formato `{ "mensagem": "...", "campos": [...] }`.

| Método | Rota | O que faz |
| ------ | ---- | --------- |
| GET | `/fontes?busca=` | Lista as fontes cadastradas, com os arquivos de cada uma |
| POST | `/fontes` | Cadastra uma fonte (a sigla não pode se repetir) |
| POST | `/fontes/{id}/arquivos` | Envia um arquivo para a zona bruta (multipart, campo `arquivo`, até 500 MB) |
| POST | `/arquivos/{id}/processar` | Valida o shapefile do CAR (`.zip`) e grava os imóveis |
| DELETE | `/arquivos/{id}` | Exclui um arquivo rejeitado |
| GET | `/imoveis/pagina?pagina=&tamanho=&municipio=&codImovel=&situacao=` | Tabela de imóveis, paginada e sem geometria |
| GET | `/imoveis/mapa?minLon=&minLat=&maxLon=&maxLat=&municipio=&situacao=` | Imóveis da área visível do mapa, em GeoJSON |
| GET | `/imoveis/{codImovel}` | Um imóvel com a geometria completa |
| GET | `/municipios?estado=` | Municípios (malha do IBGE) |

A coleção do Postman com exemplos de requisição está em [`collection_requisicoes.json`](collection_requisicoes.json).

# 🧪 Como rodar os testes
Com o projeto já clonado **e o Oracle do passo 4 no ar**, execute:

| Windows:         | `.\mvnw test` |
| ---------------- | ------------- |
| **Linux/MacOS:** | `./mvnw test` |

> [!NOTE]
> Os testes de repositório e de serviço rodam contra o Oracle do `docker-compose`, porque usam funções espaciais que não existem em banco de memória. Os testes de controller rodam sem banco.

# ⚠️ Problemas comuns
| Sintoma | Causa provável |
| ------- | -------------- |
| A aplicação não sobe e reclama de configuração da OCI | Falta o `~/.oci/config` ou o perfil `DEFAULT` (passo 3) |
| `Connection refused` na porta 1521 | O Oracle ainda está subindo; confira com `docker logs oracle` |
| `ImovelRuralRepositoryTest` falha | O imóvel de teste `TESTE-001` foi apagado do banco local; esse teste depende dele |
| Upload muito grande termina com conexão abortada | O arquivo passa do limite de 500 MB |
