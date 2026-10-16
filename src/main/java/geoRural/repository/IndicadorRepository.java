package geoRural.repository;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import geoRural.dto.IndicadorResponse;
import geoRural.dto.RegraCalculoResponse;
import geoRural.dto.VersaoDadosResponse;

/**
 * Indicadores em SQL direto: o cálculo é do pkg_indicadores, e a leitura junta seis tabelas para
 * montar o caminho até a origem (regra, versões, execuções, arquivos e fontes) de uma vez só.
 */
@Repository
public class IndicadorRepository {

    // vi/ei/ai/fi = versão do imóvel; vs/es/as_/fs = versão do insumo (RL ou focos)
    private static final String SQL_INDICADORES = """
            SELECT ii.indicador, ii.valor, ii.unidade, ii.status, ii.numerador, ii.area_imovel_ha,
                   ii.area_ha, ii.calculado_em,
                   r.codigo AS r_codigo, r.versao AS r_versao, r.nome AS r_nome, r.formula AS r_formula,
                   r.descricao AS r_descricao, r.parametros AS r_parametros,
                   vi.conjunto AS vi_conjunto, vi.versao AS vi_versao, ei.id AS vi_execucao,
                   ei.finalizada_em AS vi_quando, ai.id AS vi_arquivo_id, ai.nome_original AS vi_arquivo,
                   ai.hash_sha256 AS vi_hash, fi.sigla AS vi_fonte, fi.nome AS vi_fonte_nome,
                   vs.conjunto AS vs_conjunto, vs.versao AS vs_versao, es.id AS vs_execucao,
                   es.finalizada_em AS vs_quando, as_.id AS vs_arquivo_id, as_.nome_original AS vs_arquivo,
                   as_.hash_sha256 AS vs_hash, fs.sigla AS vs_fonte, fs.nome AS vs_fonte_nome,
                   ROW_NUMBER() OVER (PARTITION BY ii.indicador
                                      ORDER BY ii.calculado_em DESC, ii.id DESC) AS rn
              FROM indicador_imovel ii
              JOIN regra_calculo r      ON r.id   = ii.regra_calculo_id
              LEFT JOIN dataset_versao vi ON vi.id  = ii.versao_imovel_id
              LEFT JOIN execucao ei       ON ei.id  = vi.execucao_id
              LEFT JOIN arquivo_bruto ai  ON ai.id  = ei.arquivo_bruto_id
              LEFT JOIN fonte fi          ON fi.id  = ai.fonte_id
              LEFT JOIN dataset_versao vs ON vs.id  = ii.dataset_versao_id
              LEFT JOIN execucao es       ON es.id  = vs.execucao_id
              LEFT JOIN arquivo_bruto as_ ON as_.id = es.arquivo_bruto_id
              LEFT JOIN fonte fs          ON fs.id  = as_.fonte_id
             WHERE ii.cod_imovel = ?
            """;

    /** Regra ativa de um indicador, com os parâmetros que o mapa de focos também precisa. */
    public record RegraVigente(long id, String codigo, int versao, Map<String, Object> parametros) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public IndicadorRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Roda pkg_indicadores.calcular_irl na transação de quem chamou. Devolve quantos gravou. */
    public int calcularIrl() {
        return chamar("{? = call pkg_indicadores.calcular_irl()}");
    }

    /** Roda pkg_indicadores.calcular_ifc com a janela contada a partir de agora (UTC). */
    public int calcularIfc() {
        return chamar("{? = call pkg_indicadores.calcular_ifc()}");
    }

    /** O valor mais recente de cada indicador do imóvel. */
    public List<IndicadorResponse> buscarAtuais(String codImovel) {
        return jdbc.query("SELECT * FROM (" + SQL_INDICADORES + ") WHERE rn = 1 ORDER BY indicador",
                (rs, n) -> mapear(rs), codImovel);
    }

    /** Todos os cálculos de um indicador do imóvel, do mais recente para o mais antigo. */
    public List<IndicadorResponse> buscarHistorico(String codImovel, String indicador) {
        return jdbc.query("SELECT * FROM (" + SQL_INDICADORES + " AND ii.indicador = ?) ORDER BY calculado_em DESC",
                (rs, n) -> mapear(rs), codImovel, indicador);
    }

    public Optional<RegraVigente> regraVigente(String indicador) {
        return jdbc.query("""
                SELECT id, codigo, versao, parametros FROM regra_calculo
                 WHERE indicador = ? AND ativa = 1
                 ORDER BY versao DESC FETCH FIRST 1 ROWS ONLY
                """, (rs, n) -> new RegraVigente(rs.getLong("id"), rs.getString("codigo"),
                        rs.getInt("versao"), parametros(rs.getString("parametros"))), indicador)
                .stream().findFirst();
    }

    private int chamar(String chamada) {
        Integer gravados = jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (CallableStatement cs = con.prepareCall(chamada)) {
                cs.registerOutParameter(1, Types.NUMERIC);
                cs.execute();
                return cs.getInt(1);
            }
        });
        return gravados == null ? 0 : gravados;
    }

    private IndicadorResponse mapear(ResultSet rs) throws SQLException {
        return new IndicadorResponse(
                rs.getString("indicador"),
                rs.getBigDecimal("valor"),
                rs.getString("unidade"),
                rs.getString("status"),
                rs.getBigDecimal("numerador"),
                rs.getBigDecimal("area_imovel_ha"),
                rs.getBigDecimal("area_ha"),
                rs.getObject("calculado_em", OffsetDateTime.class),
                new RegraCalculoResponse(
                        rs.getString("r_codigo"),
                        rs.getInt("r_versao"),
                        rs.getString("r_nome"),
                        rs.getString("r_formula"),
                        rs.getString("r_descricao"),
                        parametros(rs.getString("r_parametros"))),
                versao(rs, "vi"),
                versao(rs, "vs"));
    }

    private static VersaoDadosResponse versao(ResultSet rs, String prefixo) throws SQLException {
        String conjunto = rs.getString(prefixo + "_conjunto");
        if (conjunto == null) {
            return null;
        }
        return new VersaoDadosResponse(
                conjunto,
                rs.getInt(prefixo + "_versao"),
                rs.getObject(prefixo + "_execucao", Long.class),
                rs.getObject(prefixo + "_quando", OffsetDateTime.class),
                rs.getString(prefixo + "_fonte"),
                rs.getString(prefixo + "_fonte_nome"),
                rs.getObject(prefixo + "_arquivo_id", Long.class),
                rs.getString(prefixo + "_arquivo"),
                rs.getString(prefixo + "_hash"));
    }

    private Map<String, Object> parametros(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            return Map.of("bruto", json);
        }
    }
}
