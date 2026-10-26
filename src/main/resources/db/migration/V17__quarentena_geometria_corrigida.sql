--------------------------------------------------------------------------------
-- GeoRural DataHub — Geometria corrigida na quarentena
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8) — abra o arquivo em UTF-8 ou os acentos quebram
-- Escopo.....: US07.T06 — corrigir a geometria pela tela da quarentena
-- Depende de.: V10__quarentena.sql (registro_quarentena)
--
--------------------------------------------------------------------------------
-- O QUE ESTE SCRIPT FAZ
--------------------------------------------------------------------------------
-- Acrescenta geometria_corrigida à registro_quarentena. É a geometria que o
-- operador salvou na tela: editada à mão (vértices no mapa) ou sugerida pela
-- correção automática (pkg_quality.corrigir_geometria) e confirmada.
--
-- Faz para a geometria o que payload_corrigido faz para os atributos: a
-- original (geometria) nunca muda, e o revalidar_registro e o pkg_tratada usam
-- NVL(geometria_corrigida, geometria).
--
--------------------------------------------------------------------------------
-- COMO RODAR
--------------------------------------------------------------------------------
-- Via Flyway, na subida da aplicação. Não editar depois de aplicado.
--------------------------------------------------------------------------------

ALTER TABLE registro_quarentena ADD (geometria_corrigida SDO_GEOMETRY);

COMMENT ON COLUMN registro_quarentena.geometria_corrigida IS
    'Geometria corrigida pelo operador (manual ou automática, SRID 4326). Nula enquanto não houver correção.';
