--------------------------------------------------------------------------------
-- GeoRural DataHub — Status SUBSTITUIDO na quarentena
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8) — abra o arquivo em UTF-8 ou os acentos quebram
-- Escopo.....: Sprint 2, Tarefa 1 — reprocessar não duplica a fila da quarentena
-- Depende de.: V10__quarentena.sql (ck_quarentena_status)
--
--------------------------------------------------------------------------------
-- O QUE ESTE SCRIPT FAZ
--------------------------------------------------------------------------------
-- Acrescenta SUBSTITUIDO aos status de análise da quarentena. É o status que o
-- pkg_tratada.substituir_pendentes dá, no fim de cada carga, aos registros que
-- ainda estavam abertos (PENDENTE ou EM_ANALISE) e que a carga nova resolveu:
--   * o mesmo arquivo foi processado de novo: os da execução anterior;
--   * outro arquivo do mesmo conjunto trouxe o mesmo código: o registro antigo.
-- Assim a fila não guarda dois registros do mesmo problema. Os já analisados
-- (CORRIGIDO, DESCARTADO, REVALIDADO) nunca mudam.
--
-- Como DESCARTADO e REVALIDADO, SUBSTITUIDO é final: nenhuma ação muda mais o
-- registro.
--
--------------------------------------------------------------------------------
-- COMO RODAR
--------------------------------------------------------------------------------
-- Via Flyway, na subida da aplicação. Não editar depois de aplicado.
--------------------------------------------------------------------------------

ALTER TABLE registro_quarentena DROP CONSTRAINT ck_quarentena_status;

ALTER TABLE registro_quarentena ADD CONSTRAINT ck_quarentena_status
    CHECK (status_analise IN ('PENDENTE', 'EM_ANALISE', 'CORRIGIDO', 'DESCARTADO', 'REVALIDADO',
                              'SUBSTITUIDO'));

COMMENT ON COLUMN registro_quarentena.status_analise IS
    'PENDENTE | EM_ANALISE | CORRIGIDO | DESCARTADO | REVALIDADO | SUBSTITUIDO (resolvido por uma carga mais nova).';
