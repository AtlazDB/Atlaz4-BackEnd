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

  -- Caminho JSON de uma coluna, já entre aspas para entrar no SQL dinâmico.
  -- O ENQUOTE_LITERAL barra um nome de coluna que quebraria o SQL.
  FUNCTION json_path(p_column IN VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN DBMS_ASSERT.ENQUOTE_LITERAL('$."' || p_column || '"');
  END json_path;

  PROCEDURE validate(p_execucao_id IN NUMBER) IS
    v_fonte_id       NUMBER;
    v_has_geometry   NUMBER;
    v_has_pr_city    NUMBER;
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

    -- Obrigatório: campo ausente, nulo ou só com espaços.
    FOR r IN (SELECT fc.nome_coluna
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND rc.obrigatorio = 1) LOOP
      EXECUTE IMMEDIATE
        'INSERT INTO staging_erro (staging_id, tipo_erro, campo, motivo)
         SELECT s.id, ''OBRIGATORIO'', :campo, ''Campo obrigatório não informado.''
           FROM staging_registro s
          WHERE s.execucao_id = :execucao
            AND TRIM(JSON_VALUE(s.atributos, ' || json_path(r.nome_coluna) || ')) IS NULL'
        USING r.nome_coluna, p_execucao_id;
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
           SELECT x.id, ''DOMINIO'', :campo, ''Valor fora da lista permitida.'', SUBSTR(x.valor, 1, 1000)
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
             SELECT x.id, ''CHAVE_DUPLICADA'', :campo, ''Chave repetida em mais de uma linha do arquivo.'', SUBSTR(x.valor, 1, 1000)
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