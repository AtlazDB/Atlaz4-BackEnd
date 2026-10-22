--------------------------------------------------------------------------------
-- GeoRural DataHub — Carga dos domínios e atualização de comentários
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8) — abra o arquivo em UTF-8 ou os acentos quebram
-- Escopo.....: Sprint 2, Tarefa 5 — subtarefa 5.2
-- Depende de.: V9 (tabelas dominio e dominio_valor)
--
-- ATENÇÃO AO NÚMERO: V13 é provisório. Confirmar com a equipe o próximo número
-- livre antes do merge e renomear o arquivo se for outro.
--
--------------------------------------------------------------------------------
-- O QUE ESTE SCRIPT FAZ
--------------------------------------------------------------------------------
-- 1. Cadastra o domínio STATUS_CAR e seus quatro valores.
-- 2. Atualiza os comentários da tabela regra_campo e das colunas recortar_pr e
--    bloquear_regressao, corrigindo a documentação das regras.
--
-- IMPORTANTE:
-- A atualização dos comentários não implementa a validação geométrica.
-- O comportamento efetivo deve ser implementado e testado no pkg_quality.
--------------------------------------------------------------------------------

-- 1. DOMÍNIO ------------------------------------------------------------------
INSERT INTO dominio (codigo, nome, descricao)
VALUES ('STATUS_CAR', 'Situação do cadastro no CAR',
        'Valores do campo ind_status do arquivo AREA_IMOVEL do CAR.');

-- 2. VALORES ------------------------------------------------------------------
INSERT INTO dominio_valor (dominio_id, valor, descricao, ordem)
SELECT id, 'AT', 'Ativo', 1 FROM dominio WHERE codigo = 'STATUS_CAR';

INSERT INTO dominio_valor (dominio_id, valor, descricao, ordem)
SELECT id, 'PE', 'Pendente', 2 FROM dominio WHERE codigo = 'STATUS_CAR';

INSERT INTO dominio_valor (dominio_id, valor, descricao, ordem)
SELECT id, 'SU', 'Suspenso', 3 FROM dominio WHERE codigo = 'STATUS_CAR';

INSERT INTO dominio_valor (dominio_id, valor, descricao, ordem)
SELECT id, 'CA', 'Cancelado', 4 FROM dominio WHERE codigo = 'STATUS_CAR';

--------------------------------------------------------------------------------
-- 3. ATUALIZAÇÃO DOS COMENTÁRIOS DA V9
--------------------------------------------------------------------------------

COMMENT ON TABLE regra_campo IS
    'Mapeamento coluna de origem -> campo de destino, com as regras de qualidade. Lida pelo pkg_quality.';

COMMENT ON COLUMN regra_campo.recortar_pr IS
    '1 = valida a relação da geometria com o Paraná: totalmente fora reprova e vai para a quarentena; parcialmente fora aprova sem recorte.';

COMMENT ON COLUMN regra_campo.bloquear_regressao IS
    '1 = aplica a regra de bloqueio de regressão. A semântica fica no pkg_quality.';

COMMIT;