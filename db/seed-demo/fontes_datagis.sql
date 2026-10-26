--------------------------------------------------------------------------------
-- GeoRural DataHub — Seed de demonstração: as 4 fontes por link do DataGIS
--------------------------------------------------------------------------------
-- NÃO é migration: fica fora do Flyway e roda à mão, no banco onde a demo
-- acontece (SQL Developer, SQLcl ou "Executar script"). Pode rodar de novo:
-- atualiza o que já existe pela sigla da fonte e pelo nome da coluna.
--
-- O que cria
--   MUN-IAT    Municípios do PR (IAT, ArcGIS)          -> municipio        (chave codibge)
--   FOCOS-IAT  Focos de calor do INPE (IAT, ArcGIS)    -> foco_calor       (chave objectid)
--   RL-IAT     Reserva Legal do CAR, PR inteiro (IAT)  -> car_reserva_legal (sem chave: carga-retrato)
--   CAR-WFS    Imóveis do CAR, PR inteiro (SICAR, WFS) -> imovel_rural     (chave cod_imovel)
--
-- Para cada uma: a fonte (modalidade LINK), as colunas mapeadas (fonte_coluna)
-- e o mapeamento (regra_campo). As outras colunas do serviço aparecem sozinhas
-- na primeira carga (detectar_colunas da DAG processar_arquivo), sem mapeamento.
--
-- Fluxo na tela: "Executar" na fonte -> DAG coleta_fontes/coletar_fonte pagina
-- o serviço e grava o zip na zona bruta -> varredor_bruta -> processar_arquivo
-- (caminho genérico) -> Tratada + quarentena -> indicadores.
--
-- Ordem sugerida na demo: MUN-IAT primeiro (é a referência do "fora do PR" e
-- do município dos imóveis), depois RL-IAT e FOCOS-IAT, e o CAR-WFS por último
-- (558 mil imóveis: a coleta leva de 1 a 2 horas).
--
-- Observações
--   * A chave dos focos é o objectid do IAT: se o IAT republicar a camada e
--     renumerar, focos podem duplicar. Chave composta (data + satélite +
--     coordenada) fica para a Sprint 3.
--   * A datahora dos focos vem como texto, em dois formatos: os antigos
--     "2015/03/11 17:19:00" e os recentes "11/12/2025 17:11:00". O FRP vem com
--     vírgula decimal ("25,9"). O mapeamento aceita os dois (formato_origem).
--   * A Reserva Legal (783 MB comprimidos) e o CAR são pesados: a regra "fora
--     do PR" usa o contorno em grade (V19) para caber no tempo.
--   * A RL não tem chave: cada carga substitui os polígonos dos imóveis que
--     vieram nela (pkg_tratada.gravar_aprovados).
--   * No CAR-WFS, a situação (status_imovel) liga o desempate de código
--     repetido (AT vence, depois o mais recente, a cancelada perde no empate).
--------------------------------------------------------------------------------

DECLARE
  v_fonte NUMBER;

  PROCEDURE fonte(p_sigla VARCHAR2, p_nome VARCHAR2, p_orgao VARCHAR2, p_periodicidade VARCHAR2,
                  p_url VARCHAR2) IS
  BEGIN
    MERGE INTO fonte d
    USING (SELECT p_sigla sigla FROM dual) s ON (d.sigla = s.sigla)
    WHEN MATCHED THEN UPDATE SET d.nome = p_nome, d.orgao = p_orgao, d.periodicidade = p_periodicidade,
         d.formato = 'GeoJSON', d.modalidade = 'LINK', d.url_origem = p_url, d.metodo_http = 'GET'
    WHEN NOT MATCHED THEN INSERT (nome, sigla, orgao, formato, periodicidade, modalidade, url_origem, metodo_http)
         VALUES (p_nome, p_sigla, p_orgao, 'GeoJSON', p_periodicidade, 'LINK', p_url, 'GET');
    SELECT id INTO v_fonte FROM fonte WHERE sigla = p_sigla;
  END fonte;

  -- Uma coluna da fonte e a regra dela. p_dominio é o código do domínio (V13).
  PROCEDURE regra(p_coluna VARCHAR2, p_ordem NUMBER, p_tipo_detectado VARCHAR2, p_tabela VARCHAR2,
                  p_campo VARCHAR2, p_tipo VARCHAR2, p_obrigatorio NUMBER DEFAULT 0, p_chave NUMBER DEFAULT 0,
                  p_formato VARCHAR2 DEFAULT NULL, p_politica VARCHAR2 DEFAULT NULL,
                  p_recortar_pr NUMBER DEFAULT 0, p_dominio VARCHAR2 DEFAULT NULL,
                  p_referencia VARCHAR2 DEFAULT NULL) IS
    v_coluna  NUMBER;
    v_dominio NUMBER;
  BEGIN
    MERGE INTO fonte_coluna d
    USING (SELECT v_fonte fonte_id, p_coluna nome_coluna FROM dual) s
       ON (d.fonte_id = s.fonte_id AND d.nome_coluna = s.nome_coluna)
    WHEN MATCHED THEN UPDATE SET d.ordem = p_ordem, d.tipo_detectado = NVL(d.tipo_detectado, p_tipo_detectado)
    WHEN NOT MATCHED THEN INSERT (fonte_id, nome_coluna, tipo_detectado, ordem)
         VALUES (v_fonte, p_coluna, p_tipo_detectado, p_ordem);
    SELECT id INTO v_coluna FROM fonte_coluna WHERE fonte_id = v_fonte AND nome_coluna = p_coluna;

    IF p_dominio IS NOT NULL THEN
      SELECT id INTO v_dominio FROM dominio WHERE codigo = p_dominio;
    END IF;

    -- Um campo de destino recebe uma coluna só (uk_regra_destino): tira a regra
    -- antiga do campo, se ela apontava para outra coluna.
    DELETE FROM regra_campo
     WHERE fonte_id = v_fonte AND LOWER(destino_tabela) = LOWER(p_tabela)
       AND LOWER(campo_destino) = LOWER(p_campo) AND fonte_coluna_id <> v_coluna;

    MERGE INTO regra_campo d
    USING (SELECT v_coluna fonte_coluna_id FROM dual) s ON (d.fonte_coluna_id = s.fonte_coluna_id)
    WHEN MATCHED THEN UPDATE SET d.destino_tabela = p_tabela, d.campo_destino = p_campo, d.tipo_dado = p_tipo,
         d.formato_origem = p_formato, d.obrigatorio = p_obrigatorio, d.chave = p_chave,
         d.politica_geometria = p_politica, d.recortar_pr = p_recortar_pr, d.dominio_id = v_dominio,
         d.referencia_cruzada = p_referencia
    WHEN NOT MATCHED THEN INSERT (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado,
         formato_origem, obrigatorio, chave, politica_geometria, recortar_pr, dominio_id, referencia_cruzada)
         VALUES (v_fonte, v_coluna, p_tabela, p_campo, p_tipo, p_formato, p_obrigatorio, p_chave,
                 p_politica, p_recortar_pr, v_dominio, p_referencia);
  END regra;
BEGIN
  -- Municípios: a referência das outras (fora do PR, município do imóvel). Sem
  -- recorte do PR (seria conferir a malha contra ela mesma). Geometria inválida
  -- é corrigida e registrada em correcao_automatica.
  fonte('MUN-IAT', 'Municípios do Paraná (IAT)', 'Instituto Água e Terra', 'anual',
        'https://geopr.iat.pr.gov.br/server/rest/services/municipios_2023/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674');
  regra('codibge',  3, 'INTEIRO',   'municipio', 'cod_ibge',  'TEXTO',     p_obrigatorio => 1, p_chave => 1);
  regra('area_km2', 4, 'DECIMAL',   'municipio', 'area_km2',  'DECIMAL');
  regra('nome',     5, 'TEXTO',     'municipio', 'nome',      'TEXTO',     p_obrigatorio => 1);
  regra('geometry', 8, 'GEOMETRIA', 'municipio', 'geometria', 'GEOMETRIA', p_obrigatorio => 1,
        p_formato => 'POLIGONO', p_politica => 'CORRIGIR_E_REGISTRAR');

  -- Focos de calor: ponto, conferido contra o PR.
  fonte('FOCOS-IAT', 'Focos de calor do INPE no Paraná (IAT)', 'INPE (via IAT/PR)', 'diária',
        'https://geopr.iat.pr.gov.br/server/rest/services/Focos_de_Calor_INPE/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674');
  regra('objectid',  1, 'INTEIRO',   'foco_calor', 'id_inpe',   'TEXTO',     p_obrigatorio => 1, p_chave => 1);
  regra('datahora',  2, 'DATA',      'foco_calor', 'data_hora', 'DATA',      p_obrigatorio => 1,
        p_formato => 'YYYY/MM/DD HH24:MI:SS|DD/MM/YYYY HH24:MI:SS');
  regra('satelite',  3, 'TEXTO',     'foco_calor', 'satelite',  'TEXTO',     p_obrigatorio => 1);
  regra('municipio', 6, 'TEXTO',     'foco_calor', 'municipio', 'TEXTO');
  regra('bioma',     7, 'TEXTO',     'foco_calor', 'bioma',     'TEXTO');
  regra('frp',      13, 'DECIMAL',   'foco_calor', 'frp',       'DECIMAL',   p_formato => 'VIRGULA');
  regra('geometry', 14, 'GEOMETRIA', 'foco_calor', 'geometria', 'GEOMETRIA', p_obrigatorio => 1,
        p_formato => 'PONTO', p_politica => 'QUARENTENA', p_recortar_pr => 1);

  -- Reserva Legal do CAR, PR inteiro (o DataGIS filtra 3 municípios; aqui where=1=1).
  fonte('RL-IAT', 'Reserva Legal do CAR no Paraná (IAT)', 'SFB/SICAR (via IAT/PR)', 'mensal',
        'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/2/query?where=1=1&outFields=*&f=geojson&outSR=4674');
  regra('cod_imovel', 4, 'TEXTO',     'car_reserva_legal', 'cod_imovel', 'TEXTO',     p_obrigatorio => 1);
  regra('num_area',   5, 'DECIMAL',   'car_reserva_legal', 'area_ha',    'DECIMAL',   p_obrigatorio => 1);
  regra('ind_status', 6, 'TEXTO',     'car_reserva_legal', 'situacao',   'TEXTO',     p_dominio => 'STATUS_CAR');
  regra('geometry',  10, 'GEOMETRIA', 'car_reserva_legal', 'geometria',  'GEOMETRIA', p_obrigatorio => 1,
        p_formato => 'POLIGONO', p_politica => 'CORRIGIR_E_REGISTRAR', p_recortar_pr => 1);

  -- Imóveis do CAR pelo WFS do SICAR, PR inteiro.
  fonte('CAR-WFS', 'Imóveis do CAR no Paraná (SICAR, WFS)', 'Serviço Florestal Brasileiro', 'mensal',
        'https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json');
  regra('cod_imovel',    1, 'TEXTO',     'imovel_rural', 'cod_imovel', 'TEXTO',     p_obrigatorio => 1, p_chave => 1);
  regra('status_imovel', 2, 'TEXTO',     'imovel_rural', 'situacao',   'TEXTO',     p_obrigatorio => 1,
        p_dominio => 'STATUS_CAR');
  regra('area',          4, 'DECIMAL',   'imovel_rural', 'area_ha',    'DECIMAL',   p_obrigatorio => 1);
  regra('municipio',     7, 'TEXTO',     'imovel_rural', 'municipio',  'TEXTO',     p_obrigatorio => 1,
        p_referencia => 'municipio');
  regra('geometry',     11, 'GEOMETRIA', 'imovel_rural', 'geometria',  'GEOMETRIA', p_obrigatorio => 1,
        p_formato => 'POLIGONO', p_politica => 'CORRIGIR_E_REGISTRAR', p_recortar_pr => 1);

  COMMIT;
END;
/
