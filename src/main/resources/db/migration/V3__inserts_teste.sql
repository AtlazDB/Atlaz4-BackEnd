--------------------------------------------------------------------------------
-- GeoRural DataHub — Dados de teste para imóvel rural
--------------------------------------------------------------------------------

-- 1. ARQUIVO BRUTO
INSERT INTO arquivo_bruto (
    fonte_id,
    chave_objeto,
    nome_original,
    tamanho_bytes,
    content_type,
    hash_sha256,
    status
)
SELECT
    id,
    'bruta/CAR/2026-09-18/teste-001_imoveis.shp',
    'imoveis-teste.shp',
    1024,
    'application/octet-stream',
    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
    'PROCESSADO'
FROM fonte
WHERE sigla = 'CAR';


-- 2. EXECUÇÃO
INSERT INTO execucao (
    arquivo_bruto_id,
    tipo,
    status,
    hash_execucao,
    dag_run_id,
    registros_lidos,
    registros_validos,
    registros_invalidos,
    finalizada_em
)
SELECT
    id,
    'VALIDACAO',
    'CONCLUIDA',
    'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
    'teste-v3-001',
    1,
    1,
    0,
    SYSTIMESTAMP
FROM arquivo_bruto
WHERE chave_objeto = 'bruta/CAR/2026-09-18/teste-001_imoveis.shp';


-- 3. DATASET / VERSÃO
INSERT INTO dataset_versao (
    conjunto,
    versao,
    execucao_id,
    chave_objeto,
    zona,
    registros
)
SELECT
    'imoveis_rurais',
    1,
    id,
    'tratada/CAR/2026-09-18/imoveis_rurais_v1',
    'TRATADA',
    1
FROM execucao
WHERE dag_run_id = 'teste-v3-001';


-- 4. IMÓVEL RURAL DE TESTE
INSERT INTO imovel_rural (
    cod_imovel,
    municipio,
    estado,
    area_ha,
    geometria,
    dataset_versao_id
)
SELECT
    'TESTE-001',
    'Londrina',
    'PR',
    100.5000,
    SDO_GEOMETRY(
            2003,
            4326,
            NULL,
            SDO_ELEM_INFO_ARRAY(
                1, 1003, 1
            ),
            SDO_ORDINATE_ARRAY(
                -51.1700, -23.3100,
                -51.1600, -23.3100,
                -51.1600, -23.3000,
                -51.1700, -23.3000,
                -51.1700, -23.3100
            )
        ),
    id
FROM dataset_versao
WHERE conjunto = 'imoveis_rurais'
  AND versao = 1;

COMMIT;

--------------------------------------------------------------------------------
-- FIM
--------------------------------------------------------------------------------