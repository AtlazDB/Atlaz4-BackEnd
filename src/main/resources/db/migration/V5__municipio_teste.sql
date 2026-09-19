--------------------------------------------------------------------------------
-- GeoRural DataHub — Município de teste e vínculo do imóvel TESTE-001
--------------------------------------------------------------------------------
-- Depende de.: V3 (imóvel TESTE-001) e V4 (tabela municipio)
--
-- Mesma cadeia de linhagem da V3: arquivo -> execução -> versão -> dado.
-- A fonte aqui é IBGE (não CAR), senão a linhagem mentiria dizendo que a
-- malha municipal saiu do shapefile do CAR.
--
-- A geometria é um RETÂNGULO APROXIMADO de Londrina, não o polígono do IBGE.
-- Existe só para a US04 ter o que desenhar antes da primeira carga real.
--------------------------------------------------------------------------------

-- 1. ARQUIVO BRUTO (IBGE) -----------------------------------------------------
INSERT INTO arquivo_bruto (
    fonte_id, chave_objeto, nome_original,
    tamanho_bytes, content_type, hash_sha256, status
)
SELECT
    id,
    'bruta/IBGE/2026-09-19/teste-002_municipios.geojson',
    'municipios-teste.geojson',
    2048,
    'application/geo+json',
    'fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210',
    'PROCESSADO'
FROM fonte
WHERE sigla = 'IBGE';


-- 2. EXECUÇÃO -----------------------------------------------------------------
INSERT INTO execucao (
    arquivo_bruto_id, tipo, status, hash_execucao, dag_run_id,
    registros_lidos, registros_validos, registros_invalidos, finalizada_em
)
SELECT
    id,
    'VALIDACAO',
    'CONCLUIDA',
    '9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba',
    'teste-v5-001',
    1, 1, 0,
    SYSTIMESTAMP
FROM arquivo_bruto
WHERE chave_objeto = 'bruta/IBGE/2026-09-19/teste-002_municipios.geojson';


-- 3. DATASET / VERSÃO ---------------------------------------------------------
INSERT INTO dataset_versao (
    conjunto, versao, execucao_id, chave_objeto, zona, registros
)
SELECT
    'municipios',
    1,
    id,
    'tratada/IBGE/2026-09-19/municipios_v1',
    'TRATADA',
    1
FROM execucao
WHERE dag_run_id = 'teste-v5-001';


-- 4. MUNICÍPIO ----------------------------------------------------------------
-- Ordem dos vértices ANTI-HORÁRIA. Em SRID geodético a Oracle usa o sentido
-- do anel para decidir o que é "dentro": anel horário significa todo o planeta
-- menos o retângulo, e SDO_AREA / SDO_RELATE passam a mentir.
INSERT INTO municipio (
    cod_ibge, nome, estado, regiao, area_km2, geometria, dataset_versao_id
)
SELECT
    '4113700',
    'Londrina',
    'PR',
    'Sul',
    1651.4000,
    SDO_GEOMETRY(
        2003,
        4326,
        NULL,
        SDO_ELEM_INFO_ARRAY(1, 1003, 1),
        SDO_ORDINATE_ARRAY(
            -51.3000, -23.4500,   -- SW
            -51.0500, -23.4500,   -- SE
            -51.0500, -23.2000,   -- NE
            -51.3000, -23.2000,   -- NW
            -51.3000, -23.4500    -- fecha
        )
    ),
    id
FROM dataset_versao
WHERE conjunto = 'municipios'
  AND versao = 1;


-- 5. VÍNCULO ------------------------------------------------------------------
-- Em produção quem faz isso é a etapa CRUZAMENTO, com SDO_RELATE.
UPDATE imovel_rural
   SET municipio_id = (SELECT id FROM municipio WHERE cod_ibge = '4113700')
 WHERE cod_imovel = 'TESTE-001';

COMMIT;
