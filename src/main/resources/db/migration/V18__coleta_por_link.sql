--------------------------------------------------------------------------------
-- GeoRural DataHub — Coleta por link no Airflow
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8) — abra o arquivo em UTF-8 ou os acentos quebram
-- Escopo.....: Sprint 2, Tarefa 1 — coleta paginada das fontes por link
-- Depende de.: V1 (fonte, arquivo_bruto) e V8 (campos da fonte por link)
--
--------------------------------------------------------------------------------
-- O QUE ESTE SCRIPT FAZ
--------------------------------------------------------------------------------
-- O "Executar agora" de uma fonte por link deixa de baixar dentro da requisição
-- da tela: com o Airflow ligado, o back só marca a fonte (coleta_solicitada_em)
-- e a DAG coleta_fontes faz a coleta paginada (um serviço como o CAR do SICAR
-- tem 558 mil registros e entrega 10 mil por pedido).
--
--   fonte.coleta_solicitada_em    pedido de coleta ainda não atendido pela DAG
--   fonte.ultima_coleta_em        quando a última coleta terminou (bem ou mal)
--   fonte.ultima_coleta_mensagem  o resultado dela, para a tela: "558.443
--                                 registros coletados", "sem mudança" ou o erro
--                                 ("vieram 10.000 de 558.443")
--   arquivo_bruto.origem          UPLOAD (enviado pela tela) ou COLETA (baixado
--                                 do link da fonte): aparece na rastreabilidade
--
--------------------------------------------------------------------------------
-- COMO RODAR
--------------------------------------------------------------------------------
-- Via Flyway, na subida da aplicação. Não editar depois de aplicado.
--------------------------------------------------------------------------------

ALTER TABLE fonte ADD (
    coleta_solicitada_em    TIMESTAMP WITH TIME ZONE,
    ultima_coleta_em        TIMESTAMP WITH TIME ZONE,
    ultima_coleta_mensagem  VARCHAR2(1000 CHAR)
);

ALTER TABLE arquivo_bruto ADD origem VARCHAR2(10 CHAR) DEFAULT 'UPLOAD' NOT NULL;

ALTER TABLE arquivo_bruto ADD CONSTRAINT ck_arquivo_origem CHECK (origem IN ('UPLOAD', 'COLETA'));

-- A DAG procura as fontes com pedido pendente a cada 2 minutos.
CREATE INDEX ix_fonte_coleta_solicitada ON fonte (coleta_solicitada_em);

COMMENT ON COLUMN fonte.coleta_solicitada_em   IS 'Pedido de coleta ("Executar agora") ainda não atendido pela DAG coleta_fontes. Nulo = nada pendente.';
COMMENT ON COLUMN fonte.ultima_coleta_em       IS 'Quando a última coleta da fonte terminou, com sucesso ou não.';
COMMENT ON COLUMN fonte.ultima_coleta_mensagem IS 'Resultado da última coleta para a tela: total coletado, "sem mudança" ou o motivo da falha.';
COMMENT ON COLUMN arquivo_bruto.origem         IS 'UPLOAD (enviado pela tela) ou COLETA (baixado do link da fonte pela DAG coleta_fontes).';
