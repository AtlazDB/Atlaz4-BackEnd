--------------------------------------------------------------------------------
-- GeoRural DataHub — Seed do mapeamento de colunas do CAR
--------------------------------------------------------------------------------
-- Banco......: Oracle Database (Autonomous / ATP)
-- Encoding...: UTF-8 (AL32UTF8) — abra o arquivo em UTF-8 ou os acentos quebram
-- Escopo.....: Sprint 2, Tarefa 5 — subtarefa 5.3
-- Depende de.: V9 (fonte_coluna, regra_campo) e V13 (domínio STATUS_CAR).
--              A fonte CAR vem da V1.
--
-- ATENÇÃO AO NÚMERO: V14 é provisório. Confirmar com a equipe o próximo número
-- livre antes do merge.
--
--------------------------------------------------------------------------------
-- O QUE ESTE SCRIPT FAZ
--------------------------------------------------------------------------------
-- 1. Registra em fonte_coluna seis colunas do arquivo AREA_IMOVEL do Paraná,
--    com tipo e três exemplos reais cada:
--      cod_imovel, municipio, num_area, ind_status  -> serão mapeadas
--      geometry                                     -> será mapeada
--      ind_tipo                                     -> fica SEM mapeamento
--                                                      (a coluna "nova" da demo)
-- 2. Mapeia as cinco primeiras em regra_campo.
--
-- As outras seis colunas do arquivo (cod_tema, nom_tema, mod_fiscal, des_condic,
-- cod_estado, dat_criaca, dat_atuali) NÃO entram no seed, para a demo mostrar
-- uma única coluna nova. Em operação, a DAG detectar_colunas as registra.
--
-- As fontes e colunas são buscadas pela sigla e pelo nome, nunca por id fixo.
--------------------------------------------------------------------------------

-- 1. COLUNAS DETECTADAS -------------------------------------------------------
INSERT INTO fonte_coluna (fonte_id, nome_coluna, tipo_detectado, exemplos, ordem)
SELECT id, 'cod_imovel', 'TEXTO',
       '["PR-4100103-72AA83CE0EA24926BAE69F7D16211C24","PR-4100103-8CB1D34D146F4E0F949B8D560703745B","PR-4100103-0886F26E36EC40FFB6DC0206CB41EB84"]',
       3
  FROM fonte WHERE sigla = 'CAR';

INSERT INTO fonte_coluna (fonte_id, nome_coluna, tipo_detectado, exemplos, ordem)
SELECT id, 'num_area', 'DECIMAL', '["14.5185","12.1354","12.0853"]', 5
  FROM fonte WHERE sigla = 'CAR';

INSERT INTO fonte_coluna (fonte_id, nome_coluna, tipo_detectado, exemplos, ordem)
SELECT id, 'ind_status', 'TEXTO', '["AT","PE","CA"]', 6
  FROM fonte WHERE sigla = 'CAR';

INSERT INTO fonte_coluna (fonte_id, nome_coluna, tipo_detectado, exemplos, ordem)
SELECT id, 'ind_tipo', 'TEXTO', '["IRU","PCT","AST"]', 7
  FROM fonte WHERE sigla = 'CAR';

INSERT INTO fonte_coluna (fonte_id, nome_coluna, tipo_detectado, exemplos, ordem)
SELECT id, 'municipio', 'TEXTO', '["Abatia","Adrianopolis","Agudos do Sul"]', 9
  FROM fonte WHERE sigla = 'CAR';

-- A geometria não é uma coluna da tabela de atributos (.dbf); o nome 'geometry'
-- é o que o pyogrio/GeoPandas usa por padrão. Conferir com a Tarefa 1.
INSERT INTO fonte_coluna (fonte_id, nome_coluna, tipo_detectado, exemplos, ordem)
SELECT id, 'geometry', 'GEOMETRIA', NULL, 13
  FROM fonte WHERE sigla = 'CAR';

-- 2. MAPEAMENTO ---------------------------------------------------------------

-- cod_imovel: chave de negócio (tem repetidos no arquivo: vão para a quarentena
-- como chave duplicada)
INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino,
                         tipo_dado, obrigatorio, chave)
SELECT c.fonte_id, c.id, 'imovel_rural', 'cod_imovel', 'TEXTO', 1, 1
  FROM fonte_coluna c JOIN fonte f ON f.id = c.fonte_id
 WHERE f.sigla = 'CAR' AND c.nome_coluna = 'cod_imovel';

-- municipio: integridade cruzada com a tabela municipio. O arquivo traz o nome
-- sem acento (ex.: Sao Jose dos Pinhais); o cruzamento por nome precisa
-- normalizar, ou usar o código IBGE embutido no cod_imovel.
INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino,
                         tipo_dado, obrigatorio, referencia_cruzada)
SELECT c.fonte_id, c.id, 'imovel_rural', 'municipio', 'TEXTO', 1, 'municipio'
  FROM fonte_coluna c JOIN fonte f ON f.id = c.fonte_id
 WHERE f.sigla = 'CAR' AND c.nome_coluna = 'municipio';

INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino,
                         tipo_dado, obrigatorio)
SELECT c.fonte_id, c.id, 'imovel_rural', 'area_ha', 'DECIMAL', 1
  FROM fonte_coluna c JOIN fonte f ON f.id = c.fonte_id
 WHERE f.sigla = 'CAR' AND c.nome_coluna = 'num_area';

-- ind_status: domínio STATUS_CAR (V13)
INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino,
                         tipo_dado, obrigatorio, dominio_id)
SELECT c.fonte_id, c.id, 'imovel_rural', 'situacao', 'TEXTO', 1,
       (SELECT id FROM dominio WHERE codigo = 'STATUS_CAR')
  FROM fonte_coluna c JOIN fonte f ON f.id = c.fonte_id
 WHERE f.sigla = 'CAR' AND c.nome_coluna = 'ind_status';

-- geometria: inválida vai para a quarentena (é o que a demo mostra);
-- recortar_pr = 1 significa conferir se está no Paraná (V13)
INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino,
                         tipo_dado, obrigatorio, politica_geometria, recortar_pr)
SELECT c.fonte_id, c.id, 'imovel_rural', 'geometria', 'GEOMETRIA', 1,
       'QUARENTENA', 1
  FROM fonte_coluna c JOIN fonte f ON f.id = c.fonte_id
 WHERE f.sigla = 'CAR' AND c.nome_coluna = 'geometry';

COMMIT;

--------------------------------------------------------------------------------
-- CONFERÊNCIA ("pronta quando" da 5.3): 5 mapeadas e 1 nova
--------------------------------------------------------------------------------
-- SELECT c.nome_coluna, c.tipo_detectado,
--        CASE WHEN c.presente_ultimo_arquivo = 0 THEN 'ausente'
--             WHEN r.id IS NOT NULL THEN 'mapeada'
--             ELSE 'nova' END AS situacao
--   FROM fonte_coluna c
--   JOIN fonte f ON f.id = c.fonte_id
--   LEFT JOIN regra_campo r ON r.fonte_coluna_id = c.id
--  WHERE f.sigla = 'CAR'
--  ORDER BY c.ordem;
--------------------------------------------------------------------------------