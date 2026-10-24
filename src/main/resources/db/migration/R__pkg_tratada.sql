CREATE OR REPLACE PACKAGE pkg_tratada AS
  -- Quem GRAVA na Zona Tratada. O pkg_quality só classifica; este pacote é o
  -- que escreve, usando o mesmo mapeamento (regra_campo) que a validação usa:
  -- a coluna de origem, a tabela e o campo de destino, o tipo e a chave.

  -- Grava na Zona Tratada um registro da quarentena que passou na revalidação
  -- (o "Revalidar" da tela), com o payload corrigido se houver, e marca o
  -- registro como REVALIDADO. Valida de novo antes de gravar: não confia em
  -- quem chamou. Não dá COMMIT: quem controla a transação é quem chama.
  --   ORA-20010: registro de quarentena não encontrado.
  --   ORA-20011: registro já encerrado (DESCARTADO, REVALIDADO ou SUBSTITUIDO).
  --   ORA-20012: o registro não passou na revalidação.
  --   ORA-20013: a fonte não tem mapeamento suficiente para gravar.
  --   ORA-20014: uma carga mais recente já atualizou este registro.
  PROCEDURE gravar_registro(p_quarentena_id IN NUMBER);

  -- Chamada no fim de cada carga, depois de gravar a quarentena dela. Encerra
  -- como SUBSTITUIDO os registros ainda abertos (PENDENTE ou EM_ANALISE) que a
  -- carga resolveu, para a fila não guardar dois registros do mesmo problema:
  --   1. o mesmo arquivo foi processado de novo: os das execuções anteriores;
  --   2. outro arquivo, de uma versão anterior do mesmo conjunto, com o mesmo
  --      código que esta carga reprovou de novo ou gravou na Tratada.
  -- A chave pode ser composta (colunas unidas por "|", como no pkg_quality).
  -- Também encerra os que a própria DAG descartou sozinha (desempate de código
  -- repetido): a carga nova tomou a mesma decisão de novo. Nunca toca no que uma
  -- pessoa analisou (CORRIGIDO, DESCARTADO pelo operador, REVALIDADO), nem em
  -- registros de outros códigos: um arquivo de um município só não encerra os
  -- pendentes dos outros. Devolve quantos encerrou. Não dá COMMIT.
  FUNCTION substituir_pendentes(p_execucao_id IN NUMBER) RETURN NUMBER;

  -- Valor de uma coluna do payload sem espaços nas pontas, e NULL para os
  -- falsos nulos (NULL, N/A, -), como o pkg_quality trata. Pública porque é
  -- chamada dentro do SQL dinâmico da gravação.
  FUNCTION limpar(p_valor IN VARCHAR2) RETURN VARCHAR2;
END pkg_tratada;
/

CREATE OR REPLACE PACKAGE BODY pkg_tratada AS

  FUNCTION limpar(p_valor IN VARCHAR2) RETURN VARCHAR2 IS
    v_valor VARCHAR2(4000) := TRIM(p_valor);
  BEGIN
    IF v_valor IS NULL OR UPPER(v_valor) IN ('NULL', 'N/A', '-') THEN
      RETURN NULL;
    END IF;
    RETURN v_valor;
  END limpar;

  -- Expressão SQL que lê uma coluna do payload já no tipo do destino. O
  -- payload vem de q (registro_quarentena) no SQL montado por gravar_registro.
  -- As conversões seguem as regras de tipo do pkg_quality (ponto decimal,
  -- data em formato_origem ou DD/MM/YYYY, booleano SIM/NÃO).
  FUNCTION expressao(p_coluna IN VARCHAR2, p_tipo IN VARCHAR2, p_formato IN VARCHAR2)
    RETURN VARCHAR2 IS
    v_texto VARCHAR2(4000);
  BEGIN
    IF p_tipo = 'GEOMETRIA' THEN
      -- A geometria não está no payload: é a que chegou no arquivo.
      RETURN 'q.geometria';
    END IF;

    v_texto := 'pkg_tratada.limpar(JSON_VALUE(NVL(q.payload_corrigido, q.payload), '
            || DBMS_ASSERT.ENQUOTE_LITERAL('$."' || p_coluna || '"')
            || ' RETURNING VARCHAR2(4000)))';

    RETURN CASE p_tipo
      -- A máscara com ponto não depende do NLS da sessão (o Java abre a
      -- sessão em pt-BR, onde o separador decimal é a vírgula).
      WHEN 'INTEIRO'  THEN 'TO_NUMBER(' || v_texto || ', ''99999999999999999999'')'
      WHEN 'DECIMAL'  THEN 'TO_NUMBER(' || v_texto || ', ''99999999999999999999.9999999999'')'
      WHEN 'DATA'     THEN 'TO_DATE(' || v_texto || ', '
                             || DBMS_ASSERT.ENQUOTE_LITERAL('FX' || NVL(p_formato, 'DD/MM/YYYY')) || ')'
      WHEN 'BOOLEANO' THEN 'CASE UPPER(' || v_texto || ') WHEN ''SIM'' THEN 1 '
                             || 'WHEN ''NÃO'' THEN 0 WHEN ''NAO'' THEN 0 END'
      ELSE v_texto
    END;
  END expressao;

  FUNCTION tem_coluna(p_tabela IN VARCHAR2, p_coluna IN VARCHAR2) RETURN BOOLEAN IS
    v_qtd NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_qtd
      FROM user_tab_columns
     WHERE table_name = UPPER(p_tabela) AND column_name = UPPER(p_coluna);
    RETURN v_qtd > 0;
  END tem_coluna;

  PROCEDURE gravar_registro(p_quarentena_id IN NUMBER) IS
    v_q          registro_quarentena%ROWTYPE;
    v_fonte_id   NUMBER;
    v_tabela     VARCHAR2(128);
    v_qtd_tab    NUMBER;
    v_resultado  CLOB;
    v_versao_id  NUMBER;
    v_versao     NUMBER;
    v_conjunto   dataset_versao.conjunto%TYPE;
    v_mais_nova  NUMBER;
    v_existia    NUMBER;

    v_origem     VARCHAR2(32767);  -- SELECT ... FROM registro_quarentena q
    v_on         VARCHAR2(4000);   -- d.chave = s.chave AND ...
    v_set        VARCHAR2(32767);  -- d.campo = s.campo, ...
    v_colunas    VARCHAR2(32767);  -- campo, campo, ...
    v_valores    VARCHAR2(32767);  -- s.campo, s.campo, ...
    v_cod_imovel VARCHAR2(4000);   -- expressão do cod_imovel, para a UF do imovel_rural

    PROCEDURE acrescentar(p_campo IN VARCHAR2, p_expr IN VARCHAR2, p_chave IN BOOLEAN) IS
      v_campo VARCHAR2(128) := DBMS_ASSERT.SIMPLE_SQL_NAME(p_campo);
    BEGIN
      v_origem  := v_origem || CASE WHEN v_origem IS NOT NULL THEN ', ' END
                            || p_expr || ' ' || v_campo;
      v_colunas := v_colunas || CASE WHEN v_colunas IS NOT NULL THEN ', ' END || v_campo;
      v_valores := v_valores || CASE WHEN v_valores IS NOT NULL THEN ', ' END || 's.' || v_campo;
      IF p_chave THEN
        v_on := v_on || CASE WHEN v_on IS NOT NULL THEN ' AND ' END
                     || 'd.' || v_campo || ' = s.' || v_campo;
      ELSE
        v_set := v_set || CASE WHEN v_set IS NOT NULL THEN ', ' END
                       || 'd.' || v_campo || ' = s.' || v_campo;
      END IF;
    END acrescentar;
  BEGIN
    -- 1. O registro, travado: dois "Revalidar" ao mesmo tempo não gravam duas vezes.
    BEGIN
      SELECT * INTO v_q FROM registro_quarentena WHERE id = p_quarentena_id FOR UPDATE;
    EXCEPTION
      WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20010, 'Registro de quarentena não encontrado: ' || p_quarentena_id);
    END;

    IF v_q.status_analise IN ('DESCARTADO', 'REVALIDADO', 'SUBSTITUIDO') THEN
      RAISE_APPLICATION_ERROR(-20011,
        'O registro ' || p_quarentena_id || ' já está ' || v_q.status_analise || ' e não muda mais.');
    END IF;

    -- 2. Valida de novo, com as mesmas regras da carga.
    v_resultado := pkg_quality.revalidar_registro(p_quarentena_id);
    IF NVL(JSON_VALUE(v_resultado, '$.status'), '-') <> 'APROVADO' THEN
      RAISE_APPLICATION_ERROR(-20012,
        'O registro ainda não passa na validação: ' || DBMS_LOB.SUBSTR(v_resultado, 1500, 1));
    END IF;

    -- 3. A fonte e a tabela de destino. Registro sem entidade (gravado antes da
    --    V10) vale se a fonte grava numa tabela só.
    SELECT a.fonte_id INTO v_fonte_id
      FROM execucao e JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
     WHERE e.id = v_q.execucao_id;

    SELECT MIN(LOWER(rc.destino_tabela)), COUNT(DISTINCT LOWER(rc.destino_tabela))
      INTO v_tabela, v_qtd_tab
      FROM regra_campo rc
     WHERE rc.fonte_id = v_fonte_id
       AND (v_q.entidade IS NULL OR LOWER(rc.destino_tabela) = LOWER(v_q.entidade));

    IF v_qtd_tab = 0 THEN
      RAISE_APPLICATION_ERROR(-20013,
        'A fonte do registro não tem mapeamento de colunas para '
        || NVL(LOWER(v_q.entidade), 'nenhuma tabela') || ': cadastre o mapeamento antes de revalidar.');
    ELSIF v_qtd_tab > 1 THEN
      RAISE_APPLICATION_ERROR(-20013,
        'O registro não diz a tabela de destino (entidade) e a fonte grava em mais de uma.');
    END IF;
    v_tabela := DBMS_ASSERT.SIMPLE_SQL_NAME(v_tabela);

    -- 4. A versão: a da carga que trouxe o registro. Ele entra na Tratada como
    --    se tivesse passado naquela carga, e a rastreabilidade aponta para o
    --    mesmo arquivo da zona bruta.
    v_versao_id := v_q.dataset_versao_id;
    IF v_versao_id IS NULL THEN
      SELECT MAX(id) INTO v_versao_id FROM dataset_versao WHERE execucao_id = v_q.execucao_id;
    END IF;
    IF v_versao_id IS NOT NULL THEN
      SELECT versao, conjunto INTO v_versao, v_conjunto FROM dataset_versao WHERE id = v_versao_id;
    END IF;

    -- 5. Monta a origem do MERGE a partir do mapeamento.
    FOR r IN (SELECT fc.nome_coluna, rc.campo_destino, rc.tipo_dado, rc.formato_origem, rc.chave
                FROM regra_campo rc
                JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
               WHERE rc.fonte_id = v_fonte_id
                 AND LOWER(rc.destino_tabela) = v_tabela
               ORDER BY rc.chave DESC, rc.id) LOOP
      acrescentar(r.campo_destino, expressao(r.nome_coluna, r.tipo_dado, r.formato_origem), r.chave = 1);
      IF LOWER(r.campo_destino) = 'cod_imovel' THEN
        v_cod_imovel := expressao(r.nome_coluna, r.tipo_dado, r.formato_origem);
      END IF;
    END LOOP;

    IF v_on IS NULL THEN
      RAISE_APPLICATION_ERROR(-20013,
        'O mapeamento de ' || v_tabela || ' não tem coluna marcada como chave: '
        || 'sem ela não dá para saber se o registro já existe.');
    END IF;

    -- Colunas que a tabela tem e o mapeamento não cobre.
    IF tem_coluna(v_tabela, 'dataset_versao_id') AND INSTR(', ' || LOWER(v_colunas) || ',', ' dataset_versao_id,') = 0 THEN
      IF v_versao_id IS NULL THEN
        RAISE_APPLICATION_ERROR(-20013,
          'A carga que trouxe o registro não criou versão de dados: não há versão para gravar.');
      END IF;
      acrescentar('dataset_versao_id', TO_CHAR(v_versao_id), FALSE);
    END IF;
    -- Regra do CAR, a mesma do GravadorImoveis.estado: UF pelo prefixo do
    -- código ("PR-4113700-..." -> "PR").
    IF v_tabela = 'imovel_rural' AND v_cod_imovel IS NOT NULL
       AND INSTR(', ' || LOWER(v_colunas) || ',', ' estado,') = 0 THEN
      acrescentar('estado',
        'CASE WHEN SUBSTR(' || v_cod_imovel || ', 3, 1) = ''-'' THEN UPPER(SUBSTR(' || v_cod_imovel || ', 1, 2)) END',
        FALSE);
    END IF;

    v_origem := 'SELECT ' || v_origem || ' FROM registro_quarentena q WHERE q.id = :id';

    -- 6. Não volta o dado para trás: se uma carga mais nova já gravou esta
    --    chave, revalidar o registro antigo apagaria a versão mais recente.
    IF v_versao IS NOT NULL AND tem_coluna(v_tabela, 'dataset_versao_id') THEN
      EXECUTE IMMEDIATE
        'SELECT MAX(dv.versao) FROM ' || v_tabela || ' d'
        || ' JOIN dataset_versao dv ON dv.id = d.dataset_versao_id'
        || ' JOIN (' || v_origem || ') s ON ' || v_on
        || ' WHERE dv.conjunto = :conjunto'
        INTO v_mais_nova USING p_quarentena_id, v_conjunto;
      IF v_mais_nova > v_versao THEN
        RAISE_APPLICATION_ERROR(-20014,
          'Uma carga mais recente (versão ' || v_mais_nova || ') já atualizou este registro. '
          || 'Revalidar o da versão ' || v_versao || ' voltaria o dado para trás: descarte-o.');
      END IF;
    END IF;

    EXECUTE IMMEDIATE
      'SELECT COUNT(*) FROM ' || v_tabela || ' d JOIN (' || v_origem || ') s ON ' || v_on
      INTO v_existia USING p_quarentena_id;

    -- 7. Grava: o mesmo MERGE pela chave que a carga faz.
    BEGIN
      EXECUTE IMMEDIATE
        'MERGE INTO ' || v_tabela || ' d USING (' || v_origem || ') s ON (' || v_on || ')'
        || CASE WHEN v_set IS NOT NULL THEN ' WHEN MATCHED THEN UPDATE SET ' || v_set END
        || ' WHEN NOT MATCHED THEN INSERT (' || v_colunas || ') VALUES (' || v_valores || ')'
        USING p_quarentena_id;
    EXCEPTION
      WHEN OTHERS THEN
        -- ORA-01400: a tabela exige um campo que o mapeamento não preenche.
        IF SQLCODE = -1400 THEN
          RAISE_APPLICATION_ERROR(-20013,
            'A tabela ' || v_tabela || ' exige um campo que o mapeamento da fonte não preenche: '
            || SUBSTR(SQLERRM, 1, 300));
        END IF;
        RAISE;
    END;

    -- Registro novo na Tratada conta na versão, como os que passaram na carga.
    IF v_existia = 0 AND v_versao_id IS NOT NULL THEN
      UPDATE dataset_versao SET registros = NVL(registros, 0) + 1 WHERE id = v_versao_id;
    END IF;

    -- 8. Encerra o registro.
    UPDATE registro_quarentena
       SET status_analise    = 'REVALIDADO',
           analisado_em      = SYSTIMESTAMP,
           dataset_versao_id = NVL(dataset_versao_id, v_versao_id)
     WHERE id = p_quarentena_id;
  END gravar_registro;

  FUNCTION substituir_pendentes(p_execucao_id IN NUMBER) RETURN NUMBER IS
    c_autor     CONSTANT VARCHAR2(100) := 'Carga (pkg_tratada.substituir_pendentes)';
    -- Quem a DAG grava em analisado_por quando descarta sozinha (gravacao.AUTOR_DESCARTE_AUTOMATICO)
    c_autor_automatico CONSTANT VARCHAR2(100) := 'DAG processar_arquivo%';
    v_arquivo_id NUMBER;
    v_fonte_id   NUMBER;
    v_versao_id  NUMBER;
    v_versao     NUMBER;
    v_conjunto   dataset_versao.conjunto%TYPE;
    v_motivo     VARCHAR2(1000);
    v_total      NUMBER := 0;
    v_chave_destino VARCHAR2(4000);  -- d.col1 || '|' || d.col2, como a chave_natural
  BEGIN
    SELECT e.arquivo_bruto_id, a.fonte_id INTO v_arquivo_id, v_fonte_id
      FROM execucao e JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
     WHERE e.id = p_execucao_id;

    SELECT MAX(id) INTO v_versao_id FROM dataset_versao WHERE execucao_id = p_execucao_id;
    IF v_versao_id IS NOT NULL THEN
      SELECT versao, conjunto INTO v_versao, v_conjunto FROM dataset_versao WHERE id = v_versao_id;
    END IF;

    v_motivo := 'Substituído pela carga da execução ' || p_execucao_id
             || CASE WHEN v_versao IS NOT NULL THEN ' (versão ' || v_versao || ' de ' || v_conjunto || ')' END
             || ', que tratou este registro de novo.';

    -- 1. O mesmo arquivo processado de novo: tudo o que ficou aberto das execuções anteriores.
    UPDATE registro_quarentena q
       SET q.status_analise = 'SUBSTITUIDO', q.justificativa = v_motivo,
           q.analisado_por = c_autor, q.analisado_em = SYSTIMESTAMP
     WHERE (q.status_analise IN ('PENDENTE', 'EM_ANALISE')
            OR (q.status_analise = 'DESCARTADO' AND q.analisado_por LIKE c_autor_automatico))
       AND q.execucao_id IN (SELECT e.id FROM execucao e
                              WHERE e.arquivo_bruto_id = v_arquivo_id AND e.id <> p_execucao_id);
    v_total := SQL%ROWCOUNT;

    IF v_conjunto IS NULL THEN
      RETURN v_total;  -- carga sem versão de dados: não há "mais nova" para comparar
    END IF;

    -- 2a. Outro arquivo, de uma versão anterior do conjunto, com um código que esta carga
    --     reprovou de novo (o problema agora está no registro novo).
    UPDATE registro_quarentena q
       SET q.status_analise = 'SUBSTITUIDO', q.justificativa = v_motivo,
           q.analisado_por = c_autor, q.analisado_em = SYSTIMESTAMP
     WHERE (q.status_analise IN ('PENDENTE', 'EM_ANALISE')
            OR (q.status_analise = 'DESCARTADO' AND q.analisado_por LIKE c_autor_automatico))
       AND q.chave_natural IS NOT NULL
       AND q.execucao_id IN (SELECT dv.execucao_id FROM dataset_versao dv
                              WHERE dv.conjunto = v_conjunto AND dv.versao < v_versao)
       AND EXISTS (SELECT 1 FROM registro_quarentena n
                    WHERE n.execucao_id = p_execucao_id AND n.chave_natural = q.chave_natural);
    v_total := v_total + SQL%ROWCOUNT;

    -- 2b. ... ou que esta carga gravou na Tratada. A chave da tabela de destino é montada
    --     como o pkg_quality monta a chave_natural: as colunas de chave na ordem da
    --     fonte_coluna, unidas por "|" (chave composta), ou a própria coluna (chave simples).
    --     Se a formatação não bater (chave numérica ou data), o registro fica pendente:
    --     nunca é encerrado por engano.
    FOR t IN (SELECT DISTINCT LOWER(rc.destino_tabela) tabela
                FROM regra_campo rc
               WHERE rc.fonte_id = v_fonte_id AND rc.chave = 1) LOOP
      IF tem_coluna(t.tabela, 'dataset_versao_id') THEN
        v_chave_destino := NULL;
        FOR c IN (SELECT rc.campo_destino
                    FROM regra_campo rc
                    JOIN fonte_coluna fc ON fc.id = rc.fonte_coluna_id
                   WHERE rc.fonte_id = v_fonte_id AND rc.chave = 1
                     AND LOWER(rc.destino_tabela) = t.tabela
                   ORDER BY fc.ordem NULLS LAST, fc.nome_coluna) LOOP
          v_chave_destino := CASE WHEN v_chave_destino IS NOT NULL THEN v_chave_destino || ' || ''|'' || ' END
                          || 'TO_CHAR(d.' || DBMS_ASSERT.SIMPLE_SQL_NAME(c.campo_destino) || ')';
        END LOOP;

        EXECUTE IMMEDIATE
          'UPDATE registro_quarentena q
              SET q.status_analise = ''SUBSTITUIDO'', q.justificativa = :motivo,
                  q.analisado_por = :autor, q.analisado_em = SYSTIMESTAMP
            WHERE (q.status_analise IN (''PENDENTE'', ''EM_ANALISE'')
                   OR (q.status_analise = ''DESCARTADO'' AND q.analisado_por LIKE :automatico))
              AND q.chave_natural IS NOT NULL
              AND LOWER(q.entidade) = :tabela
              AND q.execucao_id IN (SELECT dv.execucao_id FROM dataset_versao dv
                                     WHERE dv.conjunto = :conjunto AND dv.versao < :versao)
              AND EXISTS (SELECT 1 FROM ' || DBMS_ASSERT.SIMPLE_SQL_NAME(t.tabela) || ' d
                           WHERE ' || v_chave_destino || ' = q.chave_natural
                             AND d.dataset_versao_id = :versao_id)'
          USING v_motivo, c_autor, c_autor_automatico, t.tabela, v_conjunto, v_versao, v_versao_id;
        v_total := v_total + SQL%ROWCOUNT;
      END IF;
    END LOOP;

    RETURN v_total;
  END substituir_pendentes;

END pkg_tratada;
/
