package geoRural.quality;

import com.oracle.bmc.objectstorage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A carga genérica do Airflow (generico.py) no banco: staging → pkg_quality.validate →
 * pkg_tratada.gravar_aprovados / gravar_reprovados. Contra o Oracle do docker-compose; cada
 * teste é desfeito no fim (@Transactional).
 */
@SpringBootTest
@Transactional
class PkgTratadaCargaTest {

    /** Quadrado pequeno no PR, anel no sentido anti-horário (o que o Oracle exige). */
    private static final String QUADRADO = """
            SDO_UTIL.TO_WKBGEOMETRY(SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 1),
              SDO_ORDINATE_ARRAY(-51.01, -25.01, -51.00, -25.01, -51.00, -25.00, -51.01, -25.00, -51.01, -25.01)))""";

    @MockitoBean
    private ObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbc;

    private long fonteId;
    private int arquivos;

    @BeforeEach
    void fonte() {
        jdbc.update("""
                INSERT INTO fonte (nome, sigla, orgao, formato, modalidade, url_origem)
                VALUES ('Fonte de teste da carga genérica', 'TESTE_GEN', 'Teste', 'GeoJSON', 'LINK',
                        'https://exemplo.gov.br/arcgis/rest/services/x/FeatureServer/0/query')""");
        fonteId = jdbc.queryForObject("SELECT id FROM fonte WHERE sigla = 'TESTE_GEN'", Long.class);
    }

    @Test
    void comChaveGravaPeloMergeEMandaOReprovadoParaAQuarentena() {
        regra("codigo", 1, "municipio", "cod_ibge", "TEXTO", 1, 1, null);
        regra("nome", 2, "municipio", "nome", "TEXTO", 1, 0, null);
        regra("area", 3, "municipio", "area_km2", "DECIMAL", 0, 0, null);
        geometria("municipio", "POLIGONO");

        long execucao = execucao();
        staging(execucao, 1, "{\"codigo\": \"4199991\", \"nome\": \"Teste A\", \"area\": \"487.38854000000003\"}");
        staging(execucao, 2, "{\"codigo\": \"4199992\", \"nome\": null, \"area\": \"1\"}");
        long versao = versao(execucao, 1);

        validar(execucao);
        assertThat(gravar("gravar_aprovados", execucao, versao)).isEqualTo(1);
        assertThat(gravar("gravar_reprovados", execucao, versao)).isEqualTo(1);

        // estado sai do código IBGE (41 → PR); o DECIMAL aceita as 14 casas que o ArcGIS manda
        Map<String, Object> municipio = jdbc.queryForMap(
                "SELECT nome, estado, area_km2, dataset_versao_id FROM municipio WHERE cod_ibge = '4199991'");
        assertThat(municipio.get("ESTADO")).isEqualTo("PR");
        assertThat((BigDecimal) municipio.get("AREA_KM2")).isEqualByComparingTo("487.3885");  // a coluna tem 4 casas
        assertThat(((Number) municipio.get("DATASET_VERSAO_ID")).longValue()).isEqualTo(versao);

        Map<String, Object> reprovado = jdbc.queryForMap("""
                SELECT tipo_erro, entidade, chave_natural, numero_linha, dataset_versao_id,
                       JSON_VALUE(payload, '$.codigo') codigo, NVL2(geometria, 1, 0) tem_geometria
                  FROM registro_quarentena WHERE execucao_id = ?""", execucao);
        assertThat(reprovado.get("TIPO_ERRO")).isEqualTo("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(reprovado.get("ENTIDADE")).isEqualTo("municipio");
        assertThat(reprovado.get("CHAVE_NATURAL")).isEqualTo("4199992");
        assertThat(reprovado.get("CODIGO")).isEqualTo("4199992");
        assertThat(((Number) reprovado.get("NUMERO_LINHA")).intValue()).isEqualTo(2);
        assertThat(((Number) reprovado.get("TEM_GEOMETRIA")).intValue()).isEqualTo(1);

        // Carga nova do mesmo código: atualiza a linha e troca a versão (sem duplicar)
        long execucao2 = execucao();
        staging(execucao2, 1, "{\"codigo\": \"4199991\", \"nome\": \"Teste A2\", \"area\": \"10\"}");
        long versao2 = versao(execucao2, 2);
        validar(execucao2);
        assertThat(gravar("gravar_aprovados", execucao2, versao2)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT COUNT(*) n, MAX(nome) nome, MAX(dataset_versao_id) v FROM municipio WHERE cod_ibge = '4199991'"))
                .containsEntry("NOME", "Teste A2")
                .satisfies(m -> assertThat(((Number) m.get("N")).intValue()).isEqualTo(1))
                .satisfies(m -> assertThat(((Number) m.get("V")).longValue()).isEqualTo(versao2));
    }

    @Test
    void semChaveECargaRetratoPorImovel() {
        regra("cod_imovel", 1, "car_reserva_legal", "cod_imovel", "TEXTO", 1, 0, null);
        regra("num_area", 2, "car_reserva_legal", "area_ha", "DECIMAL", 1, 0, null);
        geometria("car_reserva_legal", "POLIGONO");

        // v1: dois polígonos de X e um de Y
        long e1 = execucao();
        staging(e1, 1, "{\"cod_imovel\": \"PR-TESTE-X\", \"num_area\": \"1.5\"}");
        staging(e1, 2, "{\"cod_imovel\": \"PR-TESTE-X\", \"num_area\": \"2.5\"}");
        staging(e1, 3, "{\"cod_imovel\": \"PR-TESTE-Y\", \"num_area\": \"3\"}");
        long v1 = versao(e1, 1);
        validar(e1);
        assertThat(gravar("gravar_aprovados", e1, v1)).isEqualTo(3);

        // v2 só traz X, com um polígono: substitui os dois de X e mantém o de Y
        long e2 = execucao();
        staging(e2, 1, "{\"cod_imovel\": \"PR-TESTE-X\", \"num_area\": \"9\"}");
        long v2 = versao(e2, 2);
        validar(e2);
        assertThat(gravar("gravar_aprovados", e2, v2)).isEqualTo(1);

        assertThat(jdbc.queryForList("""
                SELECT cod_imovel, area_ha, dataset_versao_id FROM car_reserva_legal
                 WHERE cod_imovel LIKE 'PR-TESTE-%' ORDER BY cod_imovel""")).hasSize(2)
                .satisfies(l -> {
                    assertThat(l.get(0).get("COD_IMOVEL")).isEqualTo("PR-TESTE-X");
                    assertThat((BigDecimal) l.get(0).get("AREA_HA")).isEqualByComparingTo("9");
                    assertThat(((Number) l.get(0).get("DATASET_VERSAO_ID")).longValue()).isEqualTo(v2);
                    assertThat(((Number) l.get(1).get("DATASET_VERSAO_ID")).longValue()).isEqualTo(v1);
                });
    }

    @Test
    void dataEmMilissegundosDesde1970() {
        regra("objectid", 1, "foco_calor", "id_inpe", "TEXTO", 1, 1, null);
        regra("datahora", 2, "foco_calor", "data_hora", "DATA", 1, 0, "EPOCH_MS");
        regra("satelite", 3, "foco_calor", "satelite", "TEXTO", 1, 0, null);
        geometria("foco_calor", "POLIGONO");

        long execucao = execucao();
        staging(execucao, 1, "{\"objectid\": \"TESTE-1\", \"datahora\": \"1426094340000\", \"satelite\": \"AQUA\"}");
        staging(execucao, 2, "{\"objectid\": \"TESTE-2\", \"datahora\": \"11/03/2015\", \"satelite\": \"AQUA\"}");
        long versao = versao(execucao, 1);
        validar(execucao);

        assertThat(gravar("gravar_aprovados", execucao, versao)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT TO_CHAR(data_hora, 'YYYY-MM-DD HH24:MI:SS') FROM foco_calor WHERE id_inpe = 'TESTE-1'",
                String.class)).isEqualTo("2015-03-11 17:19:00");
        assertThat(jdbc.queryForObject("""
                SELECT e.tipo_erro FROM staging_erro e JOIN staging_registro s ON s.id = e.staging_id
                 WHERE s.execucao_id = ? AND s.numero_linha = 2""", String.class, execucao))
                .isEqualTo("TIPO_DE_DADO_INVALIDO");
    }

    /** Como o IAT manda os focos: FRP com vírgula e datas em dois formatos na mesma camada. */
    @Test
    void decimalComVirgulaEDataComDuasMascaras() {
        regra("objectid", 1, "foco_calor", "id_inpe", "TEXTO", 1, 1, null);
        regra("datahora", 2, "foco_calor", "data_hora", "DATA", 1, 0, "YYYY/MM/DD HH24:MI:SS|DD/MM/YYYY HH24:MI:SS");
        regra("satelite", 3, "foco_calor", "satelite", "TEXTO", 1, 0, null);
        regra("frp", 4, "foco_calor", "frp", "DECIMAL", 0, 0, "VIRGULA");
        geometria("foco_calor", "POLIGONO");

        long execucao = execucao();
        staging(execucao, 1, "{\"objectid\": \"TESTE-V1\", \"datahora\": \"2015/03/11 17:19:00\", \"satelite\": \"AQUA\", \"frp\": \"25,9\"}");
        staging(execucao, 2, "{\"objectid\": \"TESTE-V2\", \"datahora\": \"11/12/2025 17:11:00\", \"satelite\": \"AQUA\", \"frp\": \"3.5\"}");
        staging(execucao, 3, "{\"objectid\": \"TESTE-V3\", \"datahora\": \"2025-12-11\", \"satelite\": \"AQUA\", \"frp\": \"1\"}");
        long versao = versao(execucao, 1);
        validar(execucao);

        assertThat(gravar("gravar_aprovados", execucao, versao)).isEqualTo(2);
        assertThat(gravar("gravar_reprovados", execucao, versao)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT id_inpe, TO_CHAR(data_hora, 'YYYY-MM-DD HH24:MI:SS') quando, frp FROM foco_calor
                 WHERE id_inpe LIKE 'TESTE-V%' ORDER BY id_inpe""")).satisfies(l -> {
            assertThat(l).hasSize(2);
            assertThat(l.get(0).get("QUANDO")).isEqualTo("2015-03-11 17:19:00");
            assertThat((BigDecimal) l.get(0).get("FRP")).isEqualByComparingTo("25.9");
            assertThat(l.get(1).get("QUANDO")).isEqualTo("2025-12-11 17:11:00");
        });
        assertThat(jdbc.queryForObject("SELECT tipo_erro FROM registro_quarentena WHERE execucao_id = ?", String.class, execucao))
                .isEqualTo("TIPO_DE_DADO_INVALIDO");
    }

    /** "Revalidar" de um registro de tabela sem chave (RL): entra como mais um polígono da carga. */
    @Test
    void revalidarSemChaveInsereNaVersaoDaCargaENaoVoltaParaTras() {
        regra("cod_imovel", 1, "car_reserva_legal", "cod_imovel", "TEXTO", 1, 0, null);
        regra("num_area", 2, "car_reserva_legal", "area_ha", "DECIMAL", 1, 0, null);
        geometria("car_reserva_legal", "POLIGONO");

        long e1 = execucao();
        staging(e1, 1, "{\"cod_imovel\": \"PR-TESTE-R\", \"num_area\": null}");  // reprova: área obrigatória
        long v1 = versao(e1, 1);
        validar(e1);
        gravar("gravar_aprovados", e1, v1);
        gravar("gravar_reprovados", e1, v1);
        long quarentena = jdbc.queryForObject("SELECT id FROM registro_quarentena WHERE execucao_id = ?", Long.class, e1);

        jdbc.update("UPDATE registro_quarentena SET payload_corrigido = ? WHERE id = ?",
                "{\"cod_imovel\": \"PR-TESTE-R\", \"num_area\": \"4.5\"}", quarentena);
        jdbc.update("BEGIN pkg_tratada.gravar_registro(?); END;", quarentena);

        assertThat(jdbc.queryForMap("SELECT COUNT(*) n, MAX(dataset_versao_id) v FROM car_reserva_legal WHERE cod_imovel = 'PR-TESTE-R'"))
                .satisfies(m -> assertThat(((Number) m.get("N")).intValue()).isEqualTo(1))
                .satisfies(m -> assertThat(((Number) m.get("V")).longValue()).isEqualTo(v1));
        assertThat(jdbc.queryForObject("SELECT status_analise FROM registro_quarentena WHERE id = ?", String.class, quarentena))
                .isEqualTo("REVALIDADO");

        // Uma carga mais nova já trouxe a RL desse imóvel: revalidar outro pendente da v1 voltaria o dado para trás.
        long e1b = execucao();
        staging(e1b, 1, "{\"cod_imovel\": \"PR-TESTE-S\", \"num_area\": null}");
        long v1b = versao(e1b, 2);
        validar(e1b);
        gravar("gravar_reprovados", e1b, v1b);
        long pendente = jdbc.queryForObject("SELECT id FROM registro_quarentena WHERE execucao_id = ?", Long.class, e1b);
        long e2 = execucao();
        staging(e2, 1, "{\"cod_imovel\": \"PR-TESTE-S\", \"num_area\": \"7\"}");
        long v2 = versao(e2, 3);
        validar(e2);
        gravar("gravar_aprovados", e2, v2);
        jdbc.update("UPDATE registro_quarentena SET payload_corrigido = ? WHERE id = ?",
                "{\"cod_imovel\": \"PR-TESTE-S\", \"num_area\": \"1\"}", pendente);
        assertThatThrownBy(() -> jdbc.update("BEGIN pkg_tratada.gravar_registro(?); END;", pendente))
                .hasMessageContaining("ORA-20014");
    }

    @Test
    void semMapeamentoRecusa() {
        long execucao = execucao();
        long versao = versao(execucao, 1);
        assertThatThrownBy(() -> gravar("gravar_aprovados", execucao, versao))
                .hasMessageContaining("ORA-20013");
    }

    // ----- cenário -----------------------------------------------------------------

    private void regra(String coluna, int ordem, String tabela, String campo, String tipo, int obrigatorio,
                       int chave, String formato) {
        jdbc.update("INSERT INTO fonte_coluna (fonte_id, nome_coluna, ordem) VALUES (?, ?, ?)", fonteId, coluna, ordem);
        jdbc.update("""
                INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado,
                                         formato_origem, obrigatorio, chave)
                SELECT ?, id, ?, ?, ?, ?, ?, ? FROM fonte_coluna WHERE fonte_id = ? AND nome_coluna = ?""",
                fonteId, tabela, campo, tipo, formato, obrigatorio, chave, fonteId, coluna);
    }

    private void geometria(String tabela, String formato) {
        jdbc.update("INSERT INTO fonte_coluna (fonte_id, nome_coluna, ordem) VALUES (?, 'geometry', 99)", fonteId);
        jdbc.update("""
                INSERT INTO regra_campo (fonte_id, fonte_coluna_id, destino_tabela, campo_destino, tipo_dado,
                                         formato_origem, obrigatorio, politica_geometria)
                SELECT ?, id, ?, 'geometria', 'GEOMETRIA', ?, 1, 'QUARENTENA'
                  FROM fonte_coluna WHERE fonte_id = ? AND nome_coluna = 'geometry'""",
                fonteId, tabela, formato, fonteId);
    }

    private long execucao() {
        String chave = "teste/generico/arquivo-" + (++arquivos) + ".zip";
        jdbc.update("""
                INSERT INTO arquivo_bruto (fonte_id, chave_objeto, nome_original, tamanho_bytes, hash_sha256, origem)
                VALUES (?, ?, 'arquivo.zip', 1, 'teste', 'COLETA')""", fonteId, chave);
        jdbc.update("""
                INSERT INTO execucao (arquivo_bruto_id, tipo)
                SELECT id, 'VALIDACAO' FROM arquivo_bruto WHERE chave_objeto = ?""", chave);
        return jdbc.queryForObject("""
                SELECT e.id FROM execucao e JOIN arquivo_bruto a ON a.id = e.arquivo_bruto_id
                 WHERE a.chave_objeto = ?""", Long.class, chave);
    }

    private void staging(long execucao, int linha, String atributos) {
        jdbc.update("INSERT INTO staging_registro (execucao_id, camada, numero_linha, atributos, geometria_wkb) "
                + "VALUES (?, 'parte-0001.geojson', ?, ?, " + QUADRADO + ")", execucao, linha, atributos);
    }

    private long versao(long execucao, int numero) {
        jdbc.update("INSERT INTO dataset_versao (conjunto, versao, execucao_id, zona) VALUES ('teste_generico', ?, ?, 'TRATADA')",
                numero, execucao);
        return jdbc.queryForObject("SELECT id FROM dataset_versao WHERE execucao_id = ?", Long.class, execucao);
    }

    private void validar(long execucao) {
        jdbc.update("BEGIN pkg_quality.validate(?); END;", execucao);
    }

    /** Função com DML: não roda dentro de um SELECT (ORA-14551), vai por CallableStatement. */
    private int gravar(String funcao, long execucao, long versao) {
        return jdbc.execute((Connection conexao) -> {
            try (CallableStatement chamada = conexao.prepareCall("{? = call pkg_tratada." + funcao + "(?, ?)}")) {
                chamada.registerOutParameter(1, Types.NUMERIC);
                chamada.setLong(2, execucao);
                chamada.setLong(3, versao);
                chamada.execute();
                return chamada.getInt(1);
            }
        });
    }
}
