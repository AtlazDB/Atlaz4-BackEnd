--------------------------------------------------------------------------------
-- GeoRural DataHub — pkg_indicadores: cálculo do IRL e do IFC
--------------------------------------------------------------------------------
-- Migration repetível: o Flyway reaplica sempre que o arquivo muda. Corrigir a
-- fórmula não exige uma V13 — e a mudança de regra de negócio vai para
-- regra_calculo, como versão nova, não para este código.
--
-- As funções não dão COMMIT: quem chama decide (o serviço Java roda as duas
-- numa transação só). Cada uma devolve quantos indicadores gravou.
--------------------------------------------------------------------------------

CREATE OR REPLACE PACKAGE pkg_indicadores AS

  -- IRL dos imóveis cobertos pela Reserva Legal carregada: os que têm RL e os
  -- dos municípios que vieram nos arquivos de RL. Um imóvel de município coberto
  -- sem nenhuma RL declarada fica com 0% (Crítico). p_cod_imovel limita a um.
  FUNCTION calcular_irl(p_cod_imovel IN VARCHAR2 DEFAULT NULL) RETURN NUMBER;

  -- IFC com os focos da janela da regra, contada para trás a partir de
  -- p_referencia (UTC; padrão: agora). Não calcula se não houver focos carregados.
  FUNCTION calcular_ifc(p_cod_imovel IN VARCHAR2 DEFAULT NULL,
                        p_referencia IN TIMESTAMP DEFAULT NULL) RETURN NUMBER;

END pkg_indicadores;
/

CREATE OR REPLACE PACKAGE BODY pkg_indicadores AS

  FUNCTION regra_vigente(p_indicador IN VARCHAR2) RETURN regra_calculo%ROWTYPE IS
    v_regra regra_calculo%ROWTYPE;
  BEGIN
    SELECT *
      INTO v_regra
      FROM regra_calculo
     WHERE indicador = p_indicador
       AND ativa = 1
     ORDER BY versao DESC
     FETCH FIRST 1 ROWS ONLY;
    RETURN v_regra;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RAISE_APPLICATION_ERROR(-20010, 'Nenhuma regra ativa para o indicador ' || p_indicador);
  END regra_vigente;

  FUNCTION parametro(p_regra IN regra_calculo%ROWTYPE, p_nome IN VARCHAR2) RETURN NUMBER IS
    v_valor NUMBER;
  BEGIN
    v_valor := JSON_VALUE(p_regra.parametros, '$.' || p_nome RETURNING NUMBER);
    IF v_valor IS NULL THEN
      RAISE_APPLICATION_ERROR(-20011,
        'A regra ' || p_regra.codigo || ' v' || p_regra.versao || ' não tem o parâmetro ' || p_nome);
    END IF;
    RETURN v_valor;
  END parametro;



  FUNCTION calcular_irl(p_cod_imovel IN VARCHAR2 DEFAULT NULL) RETURN NUMBER IS
    v_regra   regra_calculo%ROWTYPE := regra_vigente('IRL');
    v_minimo  NUMBER := parametro(v_regra, 'percentual_minimo');
  BEGIN
    MERGE INTO indicador_imovel d
    USING (
      SELECT c.cod_imovel, c.area_imovel, c.versao_imovel, c.area_rl, c.versao_rl,
             ROUND(c.area_rl / c.area_imovel * 100, 4)              AS valor,
             ROUND(c.area_rl - c.area_imovel * v_minimo / 100, 4)   AS saldo
        FROM (
          SELECT i.cod_imovel,
                 i.area_ha                                   AS area_imovel,
                 i.dataset_versao_id                         AS versao_imovel,
                 LEAST(NVL(rl.area_rl, 0), i.area_ha)        AS area_rl,
                 NVL(rl.versao_rl, cob.versao_rl)            AS versao_rl
            FROM imovel_rural i
            LEFT JOIN (SELECT cod_imovel,
                              SUM(area_ha)           AS area_rl,
                              MAX(dataset_versao_id) AS versao_rl
                         FROM car_reserva_legal
                        GROUP BY cod_imovel) rl
              ON rl.cod_imovel = i.cod_imovel
            LEFT JOIN (SELECT SUBSTR(cod_imovel, 4, 7) AS cod_ibge,
                              MAX(dataset_versao_id)   AS versao_rl
                         FROM car_reserva_legal
                        WHERE REGEXP_LIKE(cod_imovel, '^[A-Z]{2}-[0-9]{7}-')
                        GROUP BY SUBSTR(cod_imovel, 4, 7)) cob
              ON cob.cod_ibge = SUBSTR(i.cod_imovel, 4, 7)
           WHERE i.area_ha > 0
             AND (rl.cod_imovel IS NOT NULL OR cob.cod_ibge IS NOT NULL)
             AND (p_cod_imovel IS NULL OR i.cod_imovel = p_cod_imovel)
        ) c
    ) s
    ON (    d.cod_imovel        = s.cod_imovel
        AND d.indicador         = 'IRL'
        AND d.regra_calculo_id  = v_regra.id
        AND d.dataset_versao_id = s.versao_rl
        AND d.versao_imovel_id  = s.versao_imovel)
    WHEN MATCHED THEN UPDATE SET
         d.valor          = s.valor,
         d.status         = CASE WHEN s.valor >= v_minimo THEN 'CONFORME' ELSE 'CRITICO' END,
         d.numerador      = s.area_rl,
         d.area_imovel_ha = s.area_imovel,
         d.area_ha        = s.saldo,
         d.unidade        = v_regra.unidade,
         d.regra          = v_regra.codigo,
         d.calculado_em   = SYSTIMESTAMP
    WHEN NOT MATCHED THEN INSERT
         (cod_imovel, indicador, regra_calculo_id, dataset_versao_id, versao_imovel_id,
          valor, status, numerador, area_imovel_ha, area_ha, unidade, regra, calculado_em)
    VALUES
         (s.cod_imovel, 'IRL', v_regra.id, s.versao_rl, s.versao_imovel,
          s.valor, CASE WHEN s.valor >= v_minimo THEN 'CONFORME' ELSE 'CRITICO' END,
          s.area_rl, s.area_imovel, s.saldo, v_regra.unidade, v_regra.codigo, SYSTIMESTAMP);

    RETURN SQL%ROWCOUNT;
  END calcular_irl;


  ------------------------------------------------------------------------------
  -- IFC = focos do satélite de referência dentro do imóvel na janela
  --       / área declarada do imóvel x por_hectares
  ------------------------------------------------------------------------------
  FUNCTION calcular_ifc(p_cod_imovel IN VARCHAR2 DEFAULT NULL,
                        p_referencia IN TIMESTAMP DEFAULT NULL) RETURN NUMBER IS
    v_regra        regra_calculo%ROWTYPE := regra_vigente('IFC');
    v_por_ha       NUMBER := parametro(v_regra, 'por_hectares');
    v_meses        NUMBER := parametro(v_regra, 'janela_meses');
    v_critico      NUMBER := parametro(v_regra, 'limite_critico');
    v_satelite     VARCHAR2(40) := JSON_VALUE(v_regra.parametros, '$.satelite');
    v_fim          TIMESTAMP := NVL(p_referencia, SYS_EXTRACT_UTC(SYSTIMESTAMP));
    v_inicio       TIMESTAMP := ADD_MONTHS(v_fim, -v_meses);
    v_versao_focos NUMBER;
  BEGIN
    SELECT MAX(id) INTO v_versao_focos FROM dataset_versao WHERE conjunto = 'focos_calor';
    IF v_versao_focos IS NULL THEN
      RETURN 0;
    END IF;

    MERGE INTO indicador_imovel d
    USING (
      SELECT c.cod_imovel, c.area_imovel, c.versao_imovel, c.focos,
             ROUND(c.focos / c.area_imovel * v_por_ha, 4) AS valor
        FROM (
          SELECT i.cod_imovel,
                 i.area_ha           AS area_imovel,
                 i.dataset_versao_id AS versao_imovel,
                 NVL(f.focos, 0)     AS focos
            FROM imovel_rural i
            LEFT JOIN (
              -- Os focos guiam a busca: para cada ponto da janela, o índice
              -- espacial de imovel_rural acha os imóveis que ele toca.
              SELECT /*+ ORDERED */ im.cod_imovel, COUNT(*) AS focos
                FROM foco_calor fc, imovel_rural im
               WHERE fc.satelite  = v_satelite
                 AND fc.data_hora >= v_inicio
                 AND fc.data_hora <  v_fim
                 AND SDO_RELATE(im.geometria, fc.geometria, 'mask=ANYINTERACT') = 'TRUE'
                 AND (p_cod_imovel IS NULL OR im.cod_imovel = p_cod_imovel)
               GROUP BY im.cod_imovel
            ) f ON f.cod_imovel = i.cod_imovel
           WHERE i.area_ha > 0
             AND (p_cod_imovel IS NULL OR i.cod_imovel = p_cod_imovel)
        ) c
    ) s
    ON (    d.cod_imovel        = s.cod_imovel
        AND d.indicador         = 'IFC'
        AND d.regra_calculo_id  = v_regra.id
        AND d.dataset_versao_id = v_versao_focos
        AND d.versao_imovel_id  = s.versao_imovel)
    WHEN MATCHED THEN UPDATE SET
         d.valor          = s.valor,
         d.status         = CASE WHEN s.focos = 0 THEN 'CONFORME'
                                 WHEN s.valor >= v_critico THEN 'CRITICO'
                                 ELSE 'ALERTA' END,
         d.numerador      = s.focos,
         d.area_imovel_ha = s.area_imovel,
         d.area_ha        = NULL,
         d.unidade        = v_regra.unidade,
         d.regra          = v_regra.codigo,
         d.calculado_em   = SYSTIMESTAMP
    WHEN NOT MATCHED THEN INSERT
         (cod_imovel, indicador, regra_calculo_id, dataset_versao_id, versao_imovel_id,
          valor, status, numerador, area_imovel_ha, area_ha, unidade, regra, calculado_em)
    VALUES
         (s.cod_imovel, 'IFC', v_regra.id, v_versao_focos, s.versao_imovel,
          s.valor,
          CASE WHEN s.focos = 0 THEN 'CONFORME'
               WHEN s.valor >= v_critico THEN 'CRITICO'
               ELSE 'ALERTA' END,
          s.focos, s.area_imovel, NULL, v_regra.unidade, v_regra.codigo, SYSTIMESTAMP);

    RETURN SQL%ROWCOUNT;
  END calcular_ifc;

END pkg_indicadores;
/
