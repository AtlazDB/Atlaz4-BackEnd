--------------------------------------------------------------------------------
-- GeoRural DataHub — Gestão de Quarentena e Tabela de Domínio de Erros
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8) — abra o arquivo em UTF-8 ou os acentos quebram
-- Escopo.....: Sprint 2, Tarefa 6 — Tela da quarentena e visualizador de geometria
-- Depende de.: V1__catalogo_ingestao.sql (registro_quarentena) e V3 (execução de teste)
--
-- Roda fora de ordem (spring.flyway.out-of-order=true): em bancos que já têm a
-- V11 e a V12, ela entra depois delas. Não depende de nenhuma das duas.
-- Não editar este arquivo depois de aplicado em qualquer ambiente.
--------------------------------------------------------------------------------


--------------------------------------------------------------------------------
-- SEÇÃO 1 — TABELA TIPO_ERRO
--------------------------------------------------------------------------------
-- Os 13 códigos do documento de Qualidade de Dados. São os mesmos que o
-- pkg_quality grava em staging_erro.tipo_erro e que vão para a quarentena.
CREATE TABLE tipo_erro (
    codigo        VARCHAR2(40 CHAR)   NOT NULL,
    nome          VARCHAR2(120 CHAR)  NOT NULL,
    descricao     VARCHAR2(500 CHAR)  NOT NULL,
    severidade    VARCHAR2(20 CHAR)   DEFAULT 'ALTA' NOT NULL,
    CONSTRAINT pk_tipo_erro PRIMARY KEY (codigo),
    CONSTRAINT ck_tipo_erro_sev CHECK (severidade IN ('BAIXA', 'MEDIA', 'ALTA', 'CRITICA'))
);


--------------------------------------------------------------------------------
-- SEÇÃO 2 — CARGA DOS 13 TIPOS DE ERRO
--------------------------------------------------------------------------------
INSERT INTO tipo_erro VALUES ('CAMPO_OBRIGATORIO_AUSENTE',   'Campo obrigatório ausente',      'Campo obrigatório não veio no registro.', 'ALTA');
INSERT INTO tipo_erro VALUES ('CAMPO_EM_BRANCO',             'Campo em branco',                'Campo veio só com espaços ou com um falso nulo (NULL, N/A, -).', 'MEDIA');
INSERT INTO tipo_erro VALUES ('TIPO_DE_DADO_INVALIDO',       'Tipo de dado inválido',          'O valor não converte para o tipo esperado.', 'ALTA');
INSERT INTO tipo_erro VALUES ('VALOR_FORA_DO_DOMINIO',       'Valor fora do domínio',          'O valor não está na lista de valores permitidos.', 'MEDIA');
INSERT INTO tipo_erro VALUES ('REFERENCIA_NAO_ENCONTRADA',   'Referência não encontrada',      'O valor não existe na tabela de referência da Zona Tratada.', 'ALTA');
INSERT INTO tipo_erro VALUES ('REGRESSAO_DE_VALOR',          'Regressão de valor',             'Campo preenchido na carga anterior veio vazio.', 'MEDIA');
INSERT INTO tipo_erro VALUES ('CPF_CNPJ_INVALIDO',           'CPF/CNPJ inválido',              'CPF ou CNPJ com dígitos verificadores errados.', 'ALTA');
INSERT INTO tipo_erro VALUES ('CHAVE_NATURAL_DUPLICADA',     'Chave natural duplicada',        'Mais de um registro com a mesma chave no mesmo arquivo.', 'ALTA');
INSERT INTO tipo_erro VALUES ('GEOMETRIA_NULA',              'Geometria nula',                 'Sem geometria quando ela é obrigatória.', 'ALTA');
INSERT INTO tipo_erro VALUES ('GEOMETRIA_MALFORMADA',        'Geometria malformada',           'A geometria não pode ser lida.', 'CRITICA');
INSERT INTO tipo_erro VALUES ('GEOMETRIA_INVALIDA',          'Geometria inválida',             'Geometria topologicamente inválida (ex.: auto-interseção).', 'ALTA');
INSERT INTO tipo_erro VALUES ('GEOMETRIA_TIPO_INCOMPATIVEL', 'Tipo de geometria incompatível', 'Tipo de geometria diferente do esperado (ex.: ponto no lugar de polígono).', 'ALTA');
INSERT INTO tipo_erro VALUES ('FORA_DO_RECORTE_TERRITORIAL', 'Fora do recorte territorial',    'Registro fora do Paraná.', 'BAIXA');


--------------------------------------------------------------------------------
-- SEÇÃO 3 — REGISTRO_QUARENTENA (colunas novas e FKs)
--------------------------------------------------------------------------------
-- geometria: a do registro como chegou, para o visualizador da tela. Nula quando
-- a geometria nem pôde ser lida (GEOMETRIA_MALFORMADA).
ALTER TABLE registro_quarentena ADD (
    entidade            VARCHAR2(80 CHAR),
    chave_natural       VARCHAR2(100 CHAR),
    tipo_erro           VARCHAR2(40 CHAR),
    valor_recebido      VARCHAR2(4000 CHAR),
    status_analise      VARCHAR2(30 CHAR) DEFAULT 'PENDENTE' NOT NULL,
    payload_corrigido   CLOB,
    justificativa       VARCHAR2(1000 CHAR),
    analisado_em        TIMESTAMP WITH TIME ZONE,
    analisado_por       VARCHAR2(100 CHAR),
    dataset_versao_id   NUMBER,
    geometria           SDO_GEOMETRY
);

ALTER TABLE registro_quarentena ADD CONSTRAINT fk_quarentena_tipo_erro
    FOREIGN KEY (tipo_erro) REFERENCES tipo_erro (codigo);

ALTER TABLE registro_quarentena ADD CONSTRAINT ck_quarentena_status
    CHECK (status_analise IN ('PENDENTE', 'EM_ANALISE', 'CORRIGIDO', 'DESCARTADO', 'REVALIDADO'));


--------------------------------------------------------------------------------
-- SEÇÃO 4 — ÍNDICES
--------------------------------------------------------------------------------
CREATE INDEX ix_quarentena_status    ON registro_quarentena (status_analise);
CREATE INDEX ix_quarentena_tipo_erro ON registro_quarentena (tipo_erro);


--------------------------------------------------------------------------------
-- SEÇÃO 5 — REGISTRO DE TESTE COM AUTO-INTERSEÇÃO
--------------------------------------------------------------------------------
-- Um polígono em "gravata borboleta" para testar o mapa da tela. Pendura na
-- primeira execução do banco (a da V3); num banco sem execução, não insere nada.
INSERT INTO registro_quarentena (
    execucao_id, numero_linha, campo, motivo, payload,
    entidade, chave_natural, tipo_erro, status_analise, geometria
)
SELECT MIN(e.id), 1, 'geometria',
       'geometria inválida: Self-intersection[-49.245 -25.42]',
       '{"cod_imovel": "PR-4106902-TEST-001", "municipio": "Curitiba"}',
       'IMOVEL_RURAL', 'PR-4106902-TEST-001', 'GEOMETRIA_INVALIDA', 'PENDENTE',
       SDO_GEOMETRY(2003, 4326, NULL,
           SDO_ELEM_INFO_ARRAY(1, 1003, 1),
           SDO_ORDINATE_ARRAY(-49.25, -25.42, -49.20, -25.42, -49.20, -25.48,
                              -49.26, -25.40, -49.25, -25.42))
  FROM execucao e
HAVING MIN(e.id) IS NOT NULL;


--------------------------------------------------------------------------------
-- SEÇÃO 6 — COMENTÁRIOS NO DICIONÁRIO DE DADOS
--------------------------------------------------------------------------------
COMMENT ON TABLE tipo_erro IS 'Os 13 códigos de erro do motor de qualidade (pkg_quality). Sprint 2, Tarefa 6.';

COMMENT ON COLUMN tipo_erro.codigo        IS 'Identificador do erro (ex.: GEOMETRIA_INVALIDA).';
COMMENT ON COLUMN tipo_erro.nome          IS 'Nome amigável do erro para exibição na tela.';
COMMENT ON COLUMN tipo_erro.severidade    IS 'Severidade do erro: BAIXA, MEDIA, ALTA ou CRITICA.';

COMMENT ON COLUMN registro_quarentena.entidade          IS 'Tabela de destino do registro (ex.: IMOVEL_RURAL, CAR_RESERVA_LEGAL).';
COMMENT ON COLUMN registro_quarentena.chave_natural     IS 'Identificador do registro na origem (ex.: cod_imovel).';
COMMENT ON COLUMN registro_quarentena.tipo_erro         IS 'FK para tipo_erro. Código do erro principal que reteve o registro.';
COMMENT ON COLUMN registro_quarentena.status_analise    IS 'PENDENTE | EM_ANALISE | CORRIGIDO | DESCARTADO | REVALIDADO.';
COMMENT ON COLUMN registro_quarentena.payload_corrigido IS 'JSON com os dados corrigidos manualmente pelo operador.';
COMMENT ON COLUMN registro_quarentena.justificativa     IS 'Justificativa do operador ao descartar ou corrigir.';
COMMENT ON COLUMN registro_quarentena.analisado_em      IS 'Data/hora em que o operador concluiu a análise.';
COMMENT ON COLUMN registro_quarentena.analisado_por     IS 'Operador responsável pela análise.';
COMMENT ON COLUMN registro_quarentena.geometria         IS 'Geometria como chegou no arquivo, para o visualizador. Nula se ilegível.';

--------------------------------------------------------------------------------
-- FIM
--------------------------------------------------------------------------------