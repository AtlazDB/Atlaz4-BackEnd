CREATE OR REPLACE PACKAGE pkg_quality AS
  -- Valida as linhas da staging de uma execução contra as regras da fonte
  -- (regra_campo). Grava um erro por regra que falhou em staging_erro e marca
  -- cada linha como APROVADO ou REPROVADO. Não dá COMMIT: quem controla a
  -- transação é a DAG.
  -- ORA-20001: a fonte confere se a geometria está no PR (recortar_pr = 1) e
  -- a malha municipal do PR ainda não foi carregada.
  -- ORA-20003: o formato_origem da regra de geometria não é POLIGONO, PONTO
  -- nem LINHA.
  PROCEDURE validate(p_execucao_id IN NUMBER);

  -- Converte o WKB da staging para SDO_GEOMETRY com SRID 4326 (a DAG já
  -- reprojeta; o WKB puro não traz o SRID). WKB vazio ou quebrado devolve NULL.
  -- Pública porque o validate a chama dentro de um UPDATE.
  FUNCTION to_geometry(p_wkb IN BLOB) RETURN SDO_GEOMETRY;

  -- 1 se o valor é um CPF (11 dígitos) ou CNPJ (14) com os dígitos
  -- verificadores certos, com ou sem máscara; 0 se não. Pública porque o
  -- validate a chama dentro de um SELECT.
  FUNCTION valid_cpf_cnpj(p_value IN VARCHAR2) RETURN NUMBER;

  -- 'TRUE' se a geometria é válida na tolerância do validate; senão o
  -- código e o contexto do Oracle (ex.: '13349 [Element <1>] [Ring <1>]...').
  -- Pública para a tela da quarentena conferir a geometria editada à mão.
  FUNCTION validate_geometry(p_geom IN SDO_GEOMETRY) RETURN VARCHAR2;

  -- A correção automática do validate (CORRIGIR_E_REGISTRAR): RECTIFY e só
  -- vale se a corrigida sair válida e, num polígono, sem perder mais de 1% da
  -- área (nem zerar). NULL quando não dá para corrigir.
  -- Pública para a tela da quarentena sugerir a correção.
  FUNCTION fix_geometry(p_geom IN SDO_GEOMETRY) RETURN SDO_GEOMETRY;

  -- Valida de novo um registro da quarentena (com o payload e a geometria
  -- corrigidos, se houver) e devolve o resultado em JSON: {"status": ..., "erros": [...]}.
  -- Não grava nada: desfaz tudo antes de devolver.
  -- ORA-20002: registro de quarentena não encontrado.
  FUNCTION revalidar_registro(p_quarentena_id IN NUMBER) RETURN CLOB;

  -- Monta de novo o recorte_territorial da UF (V19): a união dos municípios,
  -- simplificada, cortada em grade de 0,25°. É o que a regra "fora do PR"
  -- compara. O validate chama sozinho quando a malha municipal muda de versão;
  -- é pública para rodar à mão depois de uma carga de municípios fora do fluxo.
  -- Transação autônoma: confirma na hora, para o índice espacial valer.
  PROCEDURE atualizar_recorte(p_uf IN VARCHAR2 DEFAULT 'PR');
END pkg_quality;
/

CREATE OR REPLACE PACKAGE BODY pkg_quality AS

  -- Tolerância da validação e da correção, em metros (o SRID 4326 é geodésico).
  -- Mesmo valor da V4.
  c_tolerance CONSTANT NUMBER := 0.05;

  -- Lado da célula da grade do recorte, em graus, e as simplificações do
  -- contorno, em metros (cada município antes da união; o contorno depois).
  c_passo_recorte      CONSTANT NUMBER := 0.25;
  c_simplifica_mun     CONSTANT NUMBER := 10;
  c_simplifica_recorte CONSTANT NUMBER := 20;

  -- Quanto a área pode mudar numa correção automática de geometria (fix_geometry).
  c_variacao_area_correcao CONSTANT NUMBER := 0.01;

  -- Margem da divisa, em metros, para "fora do PR": as bases cartográficas do CAR e
  -- da malha do IAT discordam na divisa (lotes do CAR do PR caíam até 306 m do lado
  -- de SC). Só reprova o que fica além disso: coordenada trocada, outro estado.
  c_margem_divisa CONSTANT VARCHAR2(30) := 'distance=1000 unit=M';

  -- Máscara da DATA quando a regra não traz formato_origem (Rocha: DD/MM/YYYY).
  c_date_format CONSTANT VARCHAR2(30) := 'DD/MM/YYYY';

  -- Caminho JSON de uma coluna, já entre aspas para entrar no SQL dinâmico.
  -- O ENQUOTE_LITERAL barra um nome de coluna que quebraria o SQL.
  FUNCTION json_path(p_column IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN DBMS_ASSERT.ENQUOTE_LITERAL('$."' || p_column || '"');
  END json_path;

  -- Caminho do tipo JSON da coluna: 'string', 'null' etc., ou NULL quando a
  -- chave nem veio. Separa o campo ausente do campo em branco.
  FUNCTION json_type_path(p_column IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN DBMS_ASSERT.ENQUOTE_LITERAL('$."' || p_column || '".type()');
  END json_type_path;

  -- Um WKB ruim não pode derrubar o UPDATE de todas as linhas: devolve NULL e
  -- o validate reprova só aquela linha.
  FUNCTION to_geometry(p_wkb IN BLOB) RETURN SDO_GEOMETRY IS
    v_geom SDO_GEOMETRY;
  BEGIN
    IF p_wkb IS NULL OR DBMS_LOB.GETLENGTH(p_wkb) = 0 THEN
      RETURN NULL;
    END IF;

    v_geom := SDO_UTIL.FROM_WKBGEOMETRY(p_wkb);
    v_geom.SDO_SRID := 4326;
    RETURN v_geom;
  EXCEPTION
    WHEN OTHERS THEN
      RETURN NULL;
  END to_geometry;

  -- O RECTIFY levanta exceção quando não consegue corrigir: devolve NULL e a
  -- linha vai para a quarentena. Privada: só é chamada dentro do PL/SQL.
  FUNCTION rectify(p_geom IN SDO_GEOMETRY) RETURN SDO_GEOMETRY IS
  BEGIN
    RETURN SDO_UTIL.RECTIFY_GEOMETRY(p_geom, c_tolerance);
  EXCEPTION
    WHEN OTHERS THEN
      RETURN NULL;
  END rectify;

  FUNCTION validate_geometry(p_geom IN SDO_GEOMETRY) RETURN VARCHAR2 IS
  BEGIN
    RETURN SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(p_geom, c_tolerance);
  END validate_geometry;

  FUNCTION fix_geometry(p_geom IN SDO_GEOMETRY) RETURN SDO_GEOMETRY IS
    v_fixed  SDO_GEOMETRY;
    v_antes  NUMBER;
    v_depois NUMBER;
  BEGIN
    v_fixed := rectify(p_geom);
    IF v_fixed IS NOT NULL AND validate_geometry(v_fixed) <> 'TRUE' THEN
      RETURN NULL;
    END IF;

    -- Trava de área (lição do DataGIS, V060/V063): o RECTIFY pode "consertar" um
    -- polígono jogando parte dele fora, e o resultado passa na validação. Na RL do
    -- IAT, 206 de 26 mil correções mudaram mais de 1% da área e 54 ficaram com área
    -- zero. Recusa a correção que zera ou PERDE mais de 1% da área: o registro vai
    -- para a quarentena, para uma pessoa decidir. Ganhar área é esperado: num anel
    -- que cruza ele mesmo ("borboleta"), o SDO_AREA da original subtrai um lado do
    -- outro e sai menor que a real.
    IF v_fixed IS NOT NULL AND MOD(p_geom.GET_GTYPE(), 4) = 3 THEN
      BEGIN
        v_antes  := SDO_GEOM.SDO_AREA(p_geom, c_tolerance);
        v_depois := SDO_GEOM.SDO_AREA(v_fixed, c_tolerance);
      EXCEPTION
        WHEN OTHERS THEN
          RETURN NULL;
      END;
      IF NVL(v_depois, 0) <= 0
         OR (v_antes > 0 AND v_depois < v_antes * (1 - c_variacao_area_correcao)) THEN
        RETURN NULL;
      END IF;
    END IF;
    RETURN v_fixed;
  END fix_geometry;

  FUNCTION valid_cpf_cnpj(p_value IN VARCHAR2) RETURN NUMBER IS
    v_digits  VARCHAR2(20);
    v_len     PLS_INTEGER;
    v_count   PLS_INTEGER;
    v_sum     PLS_INTEGER;
    v_weight  PLS_INTEGER;
    v_check   PLS_INTEGER;
  BEGIN
    -- Só dígitos e a máscara (ponto, traço, barra, espaço). Letra ou asterisco
    -- (documento anonimizado) não dá para validar.
    IF p_value IS NULL OR NOT REGEXP_LIKE(p_value, '^[0-9./ -]+$') THEN
      RETURN 0;
    END IF;

    v_digits := REGEXP_REPLACE(p_value, '[^0-9]', '');
    v_len := LENGTH(v_digits);
    IF v_len NOT IN (11, 14) THEN
      RETURN 0;
    END IF;

    -- Todos os dígitos iguais passam na conta, mas não são documentos de verdade.
    IF v_digits = RPAD(SUBSTR(v_digits, 1, 1), v_len, SUBSTR(v_digits, 1, 1)) THEN
      RETURN 0;
    END IF;

    -- Os dois dígitos verificadores, um de cada vez. O 1º usa os dígitos antes
    -- dele (9 no CPF, 12 no CNPJ); o 2º usa um a mais.
    FOR d IN 1 .. 2 LOOP
      v_count := v_len - 3 + d;
      v_sum := 0;
      FOR i IN 1 .. v_count LOOP
        -- CPF: pesos de 10 (ou 11) até 2. CNPJ: de 2 a 9 da direita para a
        -- esquerda, recomeçando no 2.
        v_weight := CASE WHEN v_len = 11 THEN v_count + 2 - i
                         ELSE MOD(v_count - i, 8) + 2 END;
        v_sum := v_sum + TO_NUMBER(SUBSTR(v_digits, i, 1)) * v_weight;
      END LOOP;

      -- Resto menor que 2 dá dígito 0; senão, 11 menos o resto.
      v_check := CASE WHEN MOD(v_sum, 11) < 2 THEN 0 ELSE 11 - MOD(v_sum, 11) END;
      IF v_check <> TO_NUMBER(SUBSTR(v_digits, v_count + 1, 1)) THEN
        RETURN 0;
      END IF;
    END LOOP;

    RETURN 1;
  END valid_cpf_cnpj;

  PROCEDURE atualizar_recorte(p_uf IN VARCHAR2 DEFAULT 'PR') IS
    PRAGMA AUTONOMOUS_TRANSACTION;
    v_versao   NUMBER;
    v_atual    NUMBER;
    v_qtd      NUMBER;
    v_contorno SDO_GEOMETRY;
    v_celula   SDO_GEOMETRY;
    v_pedaco   SDO_GEOMETRY;
    v_x0       NUMBER;
    v_y0       NUMBER;
    v_x1       NUMBER;
    v_y1       NUMBER;
  BEGIN
    -- Dois processamentos ao mesmo tempo não montam a grade duas vezes: o
    -- segundo espera e, ao ver que já está atualizada, sai.
    LOCK TABLE recorte_territorial IN EXCLUSIVE MODE;
    SELECT MAX(dataset_versao_id), COUNT(*) INTO v_versao, v_qtd FROM municipio WHERE estado = p_uf;
    SELECT MAX(versao_municipios) INTO v_atual FROM recorte_territorial WHERE uf = p_uf;
    IF v_qtd = 0 OR (v_atual IS NOT NULL AND v_atual = v_versao) THEN
      COMMIT;
      RETURN;
    END IF;

    SELECT SDO_UTIL.SIMPLIFY(SDO_AGGR_UNION(SDOAGGRTYPE(
             SDO_UTIL.SIMPLIFY(geometria, c_simplifica_mun, c_tolerance), c_tolerance)),
             c_simplifica_recorte, c_tolerance)
      INTO v_contorno
      FROM municipio
     WHERE estado = p_uf;

    DELETE FROM recorte_territorial WHERE uf = p_uf;

    -- Grade alinhada em múltiplos do passo, cobrindo a caixa do contorno.
    v_x0 := FLOOR(SDO_GEOM.SDO_MIN_MBR_ORDINATE(v_contorno, 1) / c_passo_recorte) * c_passo_recorte;
    v_y0 := FLOOR(SDO_GEOM.SDO_MIN_MBR_ORDINATE(v_contorno, 2) / c_passo_recorte) * c_passo_recorte;
    v_x1 := SDO_GEOM.SDO_MAX_MBR_ORDINATE(v_contorno, 1);
    v_y1 := SDO_GEOM.SDO_MAX_MBR_ORDINATE(v_contorno, 2);
    FOR x IN 0 .. CEIL((v_x1 - v_x0) / c_passo_recorte) - 1 LOOP
      FOR y IN 0 .. CEIL((v_y1 - v_y0) / c_passo_recorte) - 1 LOOP
        v_celula := SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 1), SDO_ORDINATE_ARRAY(
          v_x0 + x * c_passo_recorte,       v_y0 + y * c_passo_recorte,
          v_x0 + (x + 1) * c_passo_recorte, v_y0 + y * c_passo_recorte,
          v_x0 + (x + 1) * c_passo_recorte, v_y0 + (y + 1) * c_passo_recorte,
          v_x0 + x * c_passo_recorte,       v_y0 + (y + 1) * c_passo_recorte,
          v_x0 + x * c_passo_recorte,       v_y0 + y * c_passo_recorte));
        v_pedaco := SDO_GEOM.SDO_INTERSECTION(v_contorno, v_celula, c_tolerance);
        IF v_pedaco IS NOT NULL THEN
          INSERT INTO recorte_territorial (uf, geometria, versao_municipios) VALUES (p_uf, v_pedaco, v_versao);
        END IF;
      END LOOP;
    END LOOP;
    COMMIT;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK;
      RAISE;
  END atualizar_recorte;

  -- Condição SQL de "não é data" para a regra DATA: EPOCH_MS, ou uma ou mais
  -- máscaras separadas por "|" (inválida só se nenhuma converter). Exato (FX).
  FUNCTION data_invalida(p_formato IN VARCHAR2) RETURN VARCHAR2 IS
    v_resto   VARCHAR2(400) := NVL(p_formato, c_date_format);
    v_mascara VARCHAR2(400);
    v_pos     PLS_INTEGER;
    v_sql     VARCHAR2(4000);
  BEGIN
    IF UPPER(p_formato) = 'EPOCH_MS' THEN
      -- milissegundos desde 1970, como o ArcGIS entrega (pkg_tratada.expressao converte)
      RETURN 'NOT REGEXP_LIKE(x.valor, ''^[0-9]{1,15}$'')';
    END IF;
    LOOP
      v_pos := INSTR(v_resto, '|');
      v_mascara := TRIM(CASE WHEN v_pos > 0 THEN SUBSTR(v_resto, 1, v_pos - 1) ELSE v_resto END);
      v_sql := CASE WHEN v_sql IS NOT NULL THEN v_sql || ' AND ' END
               || 'VALIDATE_CONVERSION(x.valor AS DATE, ' || DBMS_ASSERT.ENQUOTE_LITERAL('FX' || v_mascara) || ') = 0';
      EXIT WHEN v_pos = 0;
      v_resto := SUBSTR(v_resto, v_pos + 1);
    END LOOP;
    RETURN '(' || v_sql || ')';
  END data_invalida;

  PROCEDURE validate(p_execucao_id IN NUMBER) IS
    v_fonte_id            NUMBER;
    v_geometry_column     fonte_coluna.nome_coluna%TYPE;
    v_geometry_required   NUMBER;
    v_geometry_policy     regra_campo.politica_geometria%TYPE;
    v_check_pr            regra_campo.recortar_pr%TYPE;
    v_geometry_type       regra_campo.formato_origem%TYPE;
    v_gtype               PLS_INTEGER;
    v_fixed              SDO_GEOMETRY;
    v_has_pr_city         NUMBER;
    v_invalid             VARCHAR2(400);
    v_exists              VARCHAR2(400);
    v_key_part            VARCHAR2(400);
    v_key_expr            VARCHAR2(4000);
    v_key_filled          VARCHAR2(4000);
    v_key_names           VARCHAR2(4000);
    v_key_table          regra_campo.destino_tabela%TYPE;
    v_key_column          regra_campo.campo_destino%TYPE;
  BEGIN
    -- Fonte da execução: é ela que diz quais regras valem.
    SELECT a.fonte_id
      INTO v_fonte_id
      FROM execucao e
      JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
     WHERE e.id = p_execucao_id;

    -- Coluna de geometria da fonte, se houver. Uma só: a staging guarda uma
    -- geometria por linha (geometria_wkb). O formato_origem diz o tipo esperado;
    -- sem ele, polígono (imóvel rural).
    BEGIN
      SELECT fc.nome_coluna, rc.obrigatorio, rc.politica_geometria, rc.recortar_pr,
             NVL(UPPER(TRIM(rc.formato_origem)), 'POLIGONO')
        INTO v_geometry_column, v_geometry_required, v_geometry_policy, v_check_pr,
             v_geometry_type
        FROM regra_campo rc
        JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
       WHERE rc.fonte_id = v_fonte_id
         AND rc.tipo_dado = 'GEOMETRIA'
         AND ROWNUM = 1;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        v_geometry_column := NULL;
    END;

    -- Tipo esperado no SDO_GTYPE (último dígito): 1 ponto, 2 linha, 3 polígono.
    -- O multi do mesmo tipo (5, 6, 7) também vale. Texto desconhecido é erro de
    -- configuração: melhor falhar do que reprovar todas as linhas.
    IF v_geometry_column IS NOT NULL THEN
      v_gtype := CASE v_geometry_type
                   WHEN 'PONTO'    THEN 1
                   WHEN 'LINHA'    THEN 2
                   WHEN 'POLIGONO' THEN 3
                 END;
      IF v_gtype IS NULL THEN
        RAISE_APPLICATION_ERROR(-20003,
          'formato_origem da geometria inválido: ' || v_geometry_type || ' (use POLIGONO, PONTO ou LINHA).');
      END IF;
    END IF;

    -- Trava: "fora do PR" compara a geometria com a malha municipal do IBGE.
    -- Sem nenhum município do PR carregado, todo imóvel seria reprovado e iria
    -- para a quarentena; melhor falhar a execução e dizer o que falta. Só vale
    -- quando a regra confere o PR (recortar_pr = 1).
    IF v_check_pr = 1 THEN
      SELECT COUNT(*)
        INTO v_has_pr_city
        FROM municipio
       WHERE estado = 'PR'
         AND ROWNUM = 1;

      IF v_has_pr_city = 0 THEN
        RAISE_APPLICATION_ERROR(-20001,
          'Nenhum município do PR na tabela municipio: carregue a malha do IBGE antes de validar.');
      END IF;

      -- O recorte em grade (V19) acompanha a versão da malha municipal.
      atualizar_recorte('PR');
    END IF;

    -- Obrigatório. Os códigos são os da V10 (tabela tipo_erro):
    --   CAMPO_OBRIGATORIO_AUSENTE: a chave não veio no JSON ou veio null;
    --   CAMPO_EM_BRANCO: veio, mas vazia, só com espaços ou com um falso nulo
    --   (NULL, N/A, -).
    -- A geometria não está no JSON (vem em geometria_wkb): o obrigatório dela
    -- fica no bloco da geometria (GEOMETRIA_NULA).
    FOR r IN (SELECT fc.nome_coluna
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.obrigatorio = 1
                 AND rc.tipo_dado <> 'GEOMETRIA') LOOP
      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
         SELECT x.id,
                CASE WHEN x.tipo IS NULL OR x.tipo = ''null''
                     THEN ''CAMPO_OBRIGATORIO_AUSENTE'' ELSE ''CAMPO_EM_BRANCO'' END,
                :campo,
                CASE WHEN x.tipo IS NULL OR x.tipo = ''null''
                     THEN ''Campo obrigatório não informado.''
                     ELSE ''Campo obrigatório em branco ou com falso nulo.'' END,
                SUBSTR(x.valor, 1, 1000)
           FROM (SELECT s.id,
                        JSON_VALUE(s.atributos, ' || json_type_path(r.nome_coluna) || ') tipo,
                        JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ') valor
                   FROM staging_registro s
                  WHERE s.execucao_id = :execucao) x
          WHERE x.tipo IS NULL
             OR x.tipo = ''null''
             OR TRIM(x.valor) IS NULL
             OR UPPER(TRIM(x.valor)) IN (''NULL'', ''N/A'', ''-'')'
        USING r.nome_coluna, p_execucao_id;
    END LOOP;

    -- Tipo: valor informado que não converte para o tipo da regra. Vazio e
    -- falso nulo não entram (isso é com o obrigatório). TEXTO aceita tudo e a
    -- GEOMETRIA vem em geometria_wkb.
    --   INTEIRO:  só dígitos, com sinal opcional;
    --   DECIMAL:  ponto como separador, como no CAR (Rocha); com
    --             formato_origem VIRGULA, aceita vírgula ou ponto (o IAT manda
    --             o FRP dos focos como "25,9");
    --   DATA:     formato_origem ou DD/MM/YYYY, exato (FX) (Rocha); várias
    --             máscaras separadas por "|" valem se qualquer uma converter (o
    --             IAT mistura "2015/03/11 17:19:00" e "11/12/2025 17:11:00");
    --             EPOCH_MS = milissegundos desde 1970 (datas do ArcGIS);
    --   BOOLEANO: SIM/NÃO, sem diferenciar maiúscula (Rocha); NAO sem acento
    --             também vale, porque os arquivos do CAR vêm sem acento.
    FOR r IN (SELECT fc.nome_coluna, rc.tipo_dado, rc.formato_origem
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.tipo_dado IN ('INTEIRO', 'DECIMAL', 'DATA', 'BOOLEANO')) LOOP
      v_invalid := CASE r.tipo_dado
        WHEN 'INTEIRO' THEN
          'NOT REGEXP_LIKE(x.valor, ''^[+-]?[0-9]+$'')'
        WHEN 'DECIMAL' THEN
          CASE WHEN UPPER(r.formato_origem) = 'VIRGULA'
               THEN 'NOT REGEXP_LIKE(x.valor, ''^[+-]?[0-9]*[.,]?[0-9]+$'')'
               ELSE 'NOT REGEXP_LIKE(x.valor, ''^[+-]?[0-9]*[.]?[0-9]+$'')'
          END
        WHEN 'DATA' THEN
          data_invalida(r.formato_origem)
        WHEN 'BOOLEANO' THEN
          'UPPER(x.valor) NOT IN (''SIM'', ''NÃO'', ''NAO'')'
      END;

      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
         SELECT x.id, ''TIPO_DE_DADO_INVALIDO'', :campo, :motivo, SUBSTR(x.valor, 1, 1000)
           FROM (SELECT s.id, TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) valor
                   FROM staging_registro s
                  WHERE s.execucao_id = :execucao) x
          WHERE x.valor IS NOT NULL
            AND UPPER(x.valor) NOT IN (''NULL'', ''N/A'', ''-'')
            AND ' || v_invalid
        USING r.nome_coluna, 'Valor não converte para ' || r.tipo_dado || '.', p_execucao_id;
    END LOOP;

    -- Chave natural: os valores das colunas de chave juntos com "|", na ordem
    -- da fonte_coluna. Com uma coluna só, é o próprio valor (é por ele que a
    -- DAG, a regressão e o pkg_tratada acham o registro). Vai também nas
    -- linhas reprovadas. Fica nula quando falta alguma parte: a V9 exige que
    -- a coluna de chave seja obrigatória, então o obrigatório já reprova a linha.
    -- Vem antes da geometria: a correcao_automatica grava a chave_natural.
    FOR r IN (SELECT fc.nome_coluna
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.chave = 1
               ORDER BY fc.ordem NULLS LAST, fc.nome_coluna) LOOP
      v_key_part := 'TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || '))';
      v_key_expr := CASE WHEN v_key_expr IS NULL THEN v_key_part
                         ELSE v_key_expr || ' || ''|'' || ' || v_key_part END;
      v_key_filled := CASE WHEN v_key_filled IS NOT NULL THEN v_key_filled || ' AND ' END
                      || v_key_part || ' IS NOT NULL';
      v_key_names := CASE WHEN v_key_names IS NOT NULL THEN v_key_names || ', ' END
                     || r.nome_coluna;
    END LOOP;

    IF v_key_expr IS NOT NULL THEN
      EXECUTE IMMEDIATE
        'UPDATE staging_registro s
            SET s.chave_natural = CASE WHEN ' || v_key_filled || '
                                       THEN SUBSTR(' || v_key_expr || ', 1, 400) END
          WHERE s.execucao_id = :execucao'
        USING p_execucao_id;

      -- Chave duplicada: a mesma chave em mais de uma linha da execução.
      -- Reprova todas as repetidas (não dá para saber qual está certa).
      INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
      SELECT x.id, 'CHAVE_NATURAL_DUPLICADA', SUBSTR(v_key_names, 1, 128),
             'Chave repetida em mais de uma linha do arquivo.', x.chave_natural
        FROM (SELECT s.id, s.chave_natural,
                     COUNT(*) OVER (PARTITION BY s.chave_natural) qtd
                FROM staging_registro s
               WHERE s.execucao_id = p_execucao_id
                 AND s.chave_natural IS NOT NULL) x
       WHERE x.qtd > 1;
    END IF;

    -- Regressão: campo preenchido na carga anterior que chegou vazio (Leandro).
    -- Acha o registro anterior pela chave_natural na tabela de destino da
    -- chave. Só com chave de uma coluna: sem chave não dá para achar o
    -- anterior, e a chave composta ainda não existe.
    BEGIN
      SELECT rc.destino_tabela, rc.campo_destino
        INTO v_key_table, v_key_column
        FROM regra_campo rc
       WHERE rc.fonte_id = v_fonte_id
         AND rc.chave = 1;
    EXCEPTION
      WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
        v_key_table := NULL;
    END;

    IF v_key_table IS NOT NULL THEN
      -- A geometria não está no JSON: fica de fora.
      FOR r IN (SELECT fc.nome_coluna, rc.campo_destino
                  FROM regra_campo rc
                  JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
                 WHERE rc.fonte_id = v_fonte_id
                   AND rc.bloquear_regressao = 1
                   AND rc.destino_tabela = v_key_table
                   AND rc.tipo_dado <> 'GEOMETRIA') LOOP
        -- Tabela e colunas vêm da regra_campo: o SIMPLE_SQL_NAME barra um nome
        -- que mudaria o SQL.
        EXECUTE IMMEDIATE
          'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
           SELECT x.id, ''REGRESSAO_DE_VALOR'', :campo,
                  SUBSTR(''Campo preenchido na carga anterior chegou vazio. Valor anterior: ''
                         || d.' || DBMS_ASSERT.SIMPLE_SQL_NAME(r.campo_destino) || ', 1, 400),
                  SUBSTR(x.valor, 1, 1000)
             FROM (SELECT s.id, s.chave_natural,
                          TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) valor
                     FROM staging_registro s
                    WHERE s.execucao_id = :execucao
                      AND s.chave_natural IS NOT NULL) x
             JOIN ' || DBMS_ASSERT.SIMPLE_SQL_NAME(v_key_table) || ' d
               ON d.' || DBMS_ASSERT.SIMPLE_SQL_NAME(v_key_column) || ' = x.chave_natural
            WHERE (x.valor IS NULL OR UPPER(x.valor) IN (''NULL'', ''N/A'', ''-''))
              AND d.' || DBMS_ASSERT.SIMPLE_SQL_NAME(r.campo_destino) || ' IS NOT NULL'
          USING r.nome_coluna, p_execucao_id;
      END LOOP;
    END IF;

    -- Geometria: converte o WKB e grava em staging_registro.geometria, que é a
    -- que a DAG usa no MERGE. A original continua em geometria_wkb.
    IF v_geometry_column IS NOT NULL THEN
      UPDATE staging_registro s
         SET s.geometria = to_geometry(s.geometria_wkb)
       WHERE s.execucao_id = p_execucao_id;

      -- Veio WKB, mas não converteu.
      INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo)
      SELECT s.id, 'GEOMETRIA_MALFORMADA', v_geometry_column, 'Geometria ilegível (WKB inválido).'
        FROM staging_registro s
       WHERE s.execucao_id = p_execucao_id
         AND DBMS_LOB.GETLENGTH(s.geometria_wkb) > 0
         AND s.geometria IS NULL;

      -- Não veio WKB (nulo ou vazio).
      IF v_geometry_required = 1 THEN
        INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo)
        SELECT s.id, 'GEOMETRIA_NULA', v_geometry_column, 'Geometria obrigatória não informada.'
          FROM staging_registro s
         WHERE s.execucao_id = p_execucao_id
           AND NVL(DBMS_LOB.GETLENGTH(s.geometria_wkb), 0) = 0;
      END IF;

      -- Converteu, mas não é do tipo esperado (ex.: ponto no lugar de polígono).
      -- Essas ficam de fora da validação e do "fora do PR": já estão reprovadas e
      -- não faz sentido corrigir ou conferir uma geometria do tipo errado.
      INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo)
      SELECT s.id, 'GEOMETRIA_TIPO_INCOMPATIVEL', v_geometry_column,
             'Geometria do tipo '
             || CASE s.geometria.GET_GTYPE()
                  WHEN 1 THEN 'PONTO'  WHEN 5 THEN 'MULTIPONTO'
                  WHEN 2 THEN 'LINHA'  WHEN 6 THEN 'MULTILINHA'
                  WHEN 3 THEN 'POLIGONO' WHEN 7 THEN 'MULTIPOLIGONO'
                  WHEN 4 THEN 'COLECAO'
                  ELSE TO_CHAR(s.geometria.GET_GTYPE())
                END
             || '; esperado ' || v_geometry_type || '.'
        FROM staging_registro s
       WHERE s.execucao_id = p_execucao_id
         AND s.geometria IS NOT NULL
         AND s.geometria.GET_GTYPE() NOT IN (v_gtype, v_gtype + 4);

      -- Converteu, mas é inválida. A validação é uma consulta só para a execução
      -- inteira; o loop passa só pelas inválidas, que são poucas.
      FOR r IN (SELECT x.id, x.camada, x.numero_linha, x.chave_natural, x.geometria, x.resultado
                  FROM (SELECT s.id, s.camada, s.numero_linha, s.chave_natural, s.geometria,
                               SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(s.geometria, c_tolerance) resultado
                          FROM staging_registro s
                         WHERE s.execucao_id = p_execucao_id
                           AND s.geometria IS NOT NULL
                           AND s.geometria.GET_GTYPE() IN (v_gtype, v_gtype + 4)) x
                 WHERE x.resultado <> 'TRUE') LOOP
        -- CORRIGIR_E_REGISTRAR: só vale a correção que sai válida.
        v_fixed := NULL;
        IF v_geometry_policy = 'CORRIGIR_E_REGISTRAR' THEN
          v_fixed := fix_geometry(r.geometria);
        END IF;

        IF v_fixed IS NOT NULL THEN
          INSERT INTO correcao_automatica
            (execucao_id, camada, numero_linha, chave_natural, coluna,
             erro_original, geometria_original, geometria_corrigida)
          VALUES
            (p_execucao_id, r.camada, r.numero_linha, r.chave_natural, v_geometry_column,
             SUBSTR(r.resultado, 1, 400), r.geometria, v_fixed);

          UPDATE staging_registro SET geometria = v_fixed WHERE id = r.id;
        ELSE
          -- QUARENTENA, ou não deu para corrigir.
          INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo)
          VALUES (r.id, 'GEOMETRIA_INVALIDA', v_geometry_column,
                  SUBSTR('Geometria inválida: ' || r.resultado, 1, 400));
        END IF;
      END LOOP;

      -- Fora do PR: reprova só a que fica a mais de 1 km do PR (c_margem_divisa).
      -- Compara com o contorno do estado em grade (recorte_territorial, V19),
      -- não com a malha municipal inteira: ~10x mais rápido. Parcialmente fora
      -- aprova, sem recorte. O DataGIS só confere a caixa do PR com margem; a
      -- margem de 1 km pega o mesmo erro grosseiro sem aceitar o vizinho. As que já têm erro
      -- GEOMETRIA_INVALIDA ficam de fora: estão reprovadas e o SDO_ANYINTERACT pode falhar nelas.
      -- As do tipo errado também (GEOMETRIA_TIPO_INCOMPATIVEL).
      IF v_check_pr = 1 THEN
        INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo)
        SELECT s.id, 'FORA_DO_RECORTE_TERRITORIAL', v_geometry_column, 'Geometria a mais de 1 km do Paraná.'
          FROM staging_registro s
         WHERE s.execucao_id = p_execucao_id
           AND s.geometria IS NOT NULL
           AND s.geometria.GET_GTYPE() IN (v_gtype, v_gtype + 4)
           AND NOT EXISTS (SELECT 1
                             FROM staging_erro e
                            WHERE e.staging_id = s.id
                              AND e.tipo_erro = 'GEOMETRIA_INVALIDA')
           AND NOT EXISTS (SELECT 1
                             FROM recorte_territorial r
                            WHERE r.uf = 'PR'
                              AND SDO_WITHIN_DISTANCE(r.geometria, s.geometria, c_margem_divisa) = 'TRUE');
      END IF;
    END IF;

    -- Domínio: valor informado que não está na lista (ativa) do domínio.
    -- Vazio não entra aqui: isso é com o obrigatório.
    FOR r IN (SELECT fc.nome_coluna, rc.dominio_id
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.dominio_id IS NOT NULL) LOOP
      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
         SELECT x.id, ''VALOR_FORA_DO_DOMINIO'', :campo, ''Valor fora da lista permitida.'', SUBSTR(x.valor, 1, 1000)
           FROM (SELECT s.id, TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) valor
                   FROM staging_registro s
                  WHERE s.execucao_id = :execucao) x
          WHERE x.valor IS NOT NULL
            AND NOT EXISTS (SELECT 1
                              FROM dominio_valor dv
                             WHERE dv.dominio_id = :dominio
                               AND dv.ativo = 1
                               AND dv.valor = x.valor)'
        USING r.nome_coluna, p_execucao_id, r.dominio_id;
    END LOOP;

    -- CPF/CNPJ: dígitos verificadores, com ou sem máscara. Vazio e falso nulo
    -- não entram (isso é com o obrigatório).
    FOR r IN (SELECT fc.nome_coluna
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.validar_cpf_cnpj = 1) LOOP
      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
         SELECT x.id, ''CPF_CNPJ_INVALIDO'', :campo, ''CPF ou CNPJ inválido.'', SUBSTR(x.valor, 1, 1000)
           FROM (SELECT s.id, TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) valor
                   FROM staging_registro s
                  WHERE s.execucao_id = :execucao) x
          WHERE x.valor IS NOT NULL
            AND UPPER(x.valor) NOT IN (''NULL'', ''N/A'', ''-'')
            AND pkg_quality.valid_cpf_cnpj(x.valor) = 0'
        USING r.nome_coluna, p_execucao_id;
    END LOOP;

    -- Integridade cruzada: o valor precisa existir na tabela de referência
    -- (lista fixa da V9). imovel_rural pelo cod_imovel. municipio: 7 dígitos é
    -- o código IBGE; senão, o nome sem diferenciar acento e maiúscula (o CAR
    -- traz o nome sem acento), só no PR, porque nomes se repetem entre estados.
    FOR r IN (SELECT fc.nome_coluna, rc.referencia_cruzada
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.referencia_cruzada IS NOT NULL) LOOP
      v_exists := CASE r.referencia_cruzada
        WHEN 'imovel_rural' THEN
          'SELECT 1 FROM imovel_rural i WHERE i.cod_imovel = x.valor'
        WHEN 'municipio' THEN
          'SELECT 1 FROM municipio m
            WHERE (REGEXP_LIKE(x.valor, ''^[0-9]{7}$'') AND m.cod_ibge = x.valor)
               OR (m.estado = ''PR''
                   AND NLSSORT(m.nome, ''NLS_SORT=BINARY_AI'') = NLSSORT(x.valor, ''NLS_SORT=BINARY_AI''))'
      END;

      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
         SELECT x.id, ''REFERENCIA_NAO_ENCONTRADA'', :campo, :motivo, SUBSTR(x.valor, 1, 1000)
           FROM (SELECT s.id, TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) valor
                   FROM staging_registro s
                  WHERE s.execucao_id = :execucao) x
          WHERE x.valor IS NOT NULL
            AND UPPER(x.valor) NOT IN (''NULL'', ''N/A'', ''-'')
            AND NOT EXISTS (' || v_exists || ')'
        USING r.nome_coluna, 'Valor não encontrado em ' || r.referencia_cruzada || '.', p_execucao_id;
    END LOOP;

    -- Linha com algum erro reprova; o resto aprova.
    UPDATE staging_registro s
       SET s.status = CASE
                        WHEN EXISTS (SELECT 1 FROM staging_erro e WHERE e.staging_id = s.id)
                        THEN 'REPROVADO'
                        ELSE 'APROVADO'
                      END
     WHERE s.execucao_id = p_execucao_id;
  END validate;

      FUNCTION revalidar_registro(p_quarentena_id IN NUMBER) RETURN CLOB IS
        v_q           registro_quarentena%ROWTYPE;
        v_staging_id  staging_registro.id%TYPE;
        v_result      CLOB;
      BEGIN
        -- Parte A: busca o registro (antes do savepoint, ver explicação)
        BEGIN
          SELECT * INTO v_q FROM registro_quarentena WHERE id = p_quarentena_id;
        EXCEPTION
          WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20002, 'Registro de quarentena não encontrado: ' || p_quarentena_id);
        END;

        -- Parte B: marca o ponto de volta e monta a staging só com este registro.
        SAVEPOINT sp_revalidar;

        -- O validate olha todas as linhas da execução. Tirando as que sobraram,
        -- a linha revalidada não acusa chave duplicada com ela mesma.
        DELETE FROM staging_registro WHERE execucao_id = v_q.execucao_id;

        INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos, geometria_wkb)
        VALUES (v_q.execucao_id,
                'REVALIDACAO',
                NVL(v_q.numero_linha, 0),
                NVL(v_q.payload_corrigido, v_q.payload),
                -- A corrigida pelo operador, se houver; senão a que chegou.
                CASE WHEN NVL(v_q.geometria_corrigida, v_q.geometria) IS NOT NULL
                     THEN SDO_UTIL.TO_WKBGEOMETRY(NVL(v_q.geometria_corrigida, v_q.geometria)) END)
        RETURNING id INTO v_staging_id;

        -- Parte C: valida e monta o JSON com o status e os erros.
        validate(v_q.execucao_id);

        SELECT JSON_OBJECT(
                 'status' VALUE s.status,
                 'erros'  VALUE COALESCE(
                            (SELECT JSON_ARRAYAGG(
                                      JSON_OBJECT('tipo_erro'      VALUE e.tipo_erro,
                                                  'campo'          VALUE e.campo,
                                                  'motivo'         VALUE e.motivo,
                                                  'valor_recebido' VALUE e.valor_recebido)
                                      ORDER BY e.id RETURNING CLOB)
                               FROM staging_erro e
                              WHERE e.staging_id = s.id),
                            TO_CLOB('[]')) FORMAT JSON
                 RETURNING CLOB)
          INTO v_result
          FROM staging_registro s
         WHERE s.id = v_staging_id;

        -- Parte D: desfaz tudo e devolve. O JSON está numa variável, não numa
        -- tabela, então o rollback não apaga.
        ROLLBACK TO SAVEPOINT sp_revalidar;
        RETURN v_result;
      EXCEPTION
        WHEN OTHERS THEN
          ROLLBACK TO SAVEPOINT sp_revalidar;
          RAISE;
      END revalidar_registro;

END pkg_quality;
/