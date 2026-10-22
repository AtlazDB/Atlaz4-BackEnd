CREATE OR REPLACE PACKAGE pkg_quality AS
  -- Valida as linhas da staging de uma execução contra as regras da fonte
  -- (regra_campo). Grava um erro por regra que falhou em staging_erro e marca
  -- cada linha como APROVADO ou REPROVADO. Não dá COMMIT: quem controla a
  -- transação é a DAG.
  -- ORA-20001: a fonte tem coluna de geometria e a malha municipal do PR
  -- ainda não foi carregada.
  PROCEDURE validate(p_execucao_id IN NUMBER);
END pkg_quality;
/

CREATE OR REPLACE PACKAGE BODY pkg_quality AS

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

  PROCEDURE validate(p_execucao_id IN NUMBER) IS
    v_fonte_id       NUMBER;
    v_has_geometry   NUMBER;
    v_has_pr_city    NUMBER;
    v_invalid        VARCHAR2(400);
  BEGIN
    -- Fonte da execução: é ela que diz quais regras valem.
    SELECT a.fonte_id
      INTO v_fonte_id
      FROM execucao e
      JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
     WHERE e.id = p_execucao_id;

    -- Trava: "fora do PR" compara a geometria com a malha municipal do IBGE.
    -- Sem nenhum município do PR carregado, todo imóvel seria reprovado e iria
    -- para a quarentena; melhor falhar a execução e dizer o que falta.
    SELECT COUNT(*)
      INTO v_has_geometry
      FROM regra_campo
     WHERE fonte_id = v_fonte_id
       AND tipo_dado = 'GEOMETRIA'
       AND ROWNUM = 1;

    IF v_has_geometry > 0 THEN
      SELECT COUNT(*)
        INTO v_has_pr_city
        FROM municipio
       WHERE estado = 'PR'
         AND ROWNUM = 1;

      IF v_has_pr_city = 0 THEN
        RAISE_APPLICATION_ERROR(-20001,
          'Nenhum município do PR na tabela municipio: carregue a malha do IBGE antes de validar.');
      END IF;
    END IF;

    -- Obrigatório. Os códigos são os da V10 (tabela tipo_erro):
    --   CAMPO_OBRIGATORIO_AUSENTE: a chave não veio no JSON ou veio null;
    --   CAMPO_EM_BRANCO: veio, mas vazia, só com espaços ou com um falso nulo
    --   (NULL, N/A, -).
    FOR r IN (SELECT fc.nome_coluna
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.obrigatorio = 1) LOOP
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
    --   DECIMAL:  ponto como separador, como no CAR (Rocha);
    --   DATA:     formato_origem ou DD/MM/YYYY, exato (FX) (Rocha);
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
          'NOT REGEXP_LIKE(x.valor, ''^[+-]?[0-9]*[.]?[0-9]+$'')'
        WHEN 'DATA' THEN
          'VALIDATE_CONVERSION(x.valor AS DATE, '
             || DBMS_ASSERT.ENQUOTE_LITERAL('FX' || NVL(r.formato_origem, c_date_format)) || ') = 0'
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

    -- Chave duplicada: o mesmo valor de chave em mais de uma linha da execução.
    -- Reprova todas as repetidas (não dá para saber qual está certa).
    -- Supõe chave de uma coluna só: confirmar com o Rocha.
    FOR r IN (SELECT fc.nome_coluna
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.chave = 1) LOOP
      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo, valor_recebido)
         SELECT x.id, ''CHAVE_NATURAL_DUPLICADA'', :campo, ''Chave repetida em mais de uma linha do arquivo.'', SUBSTR(x.valor, 1, 1000)
           FROM (SELECT y.id, y.valor, COUNT(*) OVER (PARTITION BY y.valor) qtd
                   FROM (SELECT s.id, TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) valor
                           FROM staging_registro s
                          WHERE s.execucao_id = :execucao) y) x
          WHERE x.valor IS NOT NULL
            AND x.qtd > 1'
        USING r.nome_coluna, p_execucao_id;

      -- Chave natural: o valor da coluna de chave, para a DAG e a quarentena
      -- acharem o registro. Vai também nas linhas reprovadas.
      -- Corta em 80 (tamanho da coluna) até o Rocha confirmar se a chave pode ser composta.
      EXECUTE IMMEDIATE
        'UPDATE staging_registro s
            SET s.chave_natural = SUBSTR(TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')), 1, 80)
          WHERE s.execucao_id = :execucao'
        USING p_execucao_id;
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

END pkg_quality;
/