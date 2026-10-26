--------------------------------------------------------------------------------
-- GeoRural DataHub — Chave natural composta e nomes de coluna com 128
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8)
-- Escopo.....: Sprint 2, Tarefa 2 — ajuste da V11 e da V10
-- Depende de.: V1, V9, V10 e V11
--
-- chave_natural: uma fonte pode ter mais de uma coluna com chave = 1; o
-- pkg_quality junta os valores com "|", e 80 (ou 100) não bastam.
-- campo/coluna: a V9 aceita nomes de coluna com até 128 caracteres
-- (fonte_coluna.nome_coluna), e a staging e a quarentena guardavam só 80.
-- Aumentar o tamanho não perde dados e mantém os comentários das colunas.
-- Nenhuma dessas colunas tem índice.
-- Não editar este arquivo depois de aplicado em qualquer ambiente.
--------------------------------------------------------------------------------

ALTER TABLE staging_registro    MODIFY (chave_natural VARCHAR2(400 CHAR));

ALTER TABLE staging_erro        MODIFY (campo VARCHAR2(128 CHAR));

ALTER TABLE correcao_automatica MODIFY (chave_natural VARCHAR2(400 CHAR),
                                        coluna        VARCHAR2(128 CHAR));

-- A DAG copia a chave_natural e o campo da staging para a quarentena.
ALTER TABLE registro_quarentena MODIFY (chave_natural VARCHAR2(400 CHAR),
                                        campo         VARCHAR2(128 CHAR));
