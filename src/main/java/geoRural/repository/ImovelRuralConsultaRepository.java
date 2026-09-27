package geoRural.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import geoRural.dto.ImovelResumoResponse;

/**
 * Consulta paginada da tabela de imóveis, em SQL direto: o Spring Data não sabe calcular a caixa
 * envolvente de um SDO_GEOMETRY, e carregar a entidade traria a geometria inteira de cada imóvel.
 */
@Repository
public class ImovelRuralConsultaRepository {

    private final JdbcTemplate jdbc;

    public ImovelRuralConsultaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long contar(String municipio, String codImovel) {
        List<Object> parametros = new ArrayList<>();
        String where = filtro(municipio, codImovel, parametros);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM imovel_rural" + where, Long.class, parametros.toArray());
        return total == null ? 0 : total;
    }

    /** {@code pagina} começa em 1. */
    public List<ImovelResumoResponse> buscarPagina(String municipio, String codImovel, int pagina, int tamanho) {
        List<Object> parametros = new ArrayList<>();
        String where = filtro(municipio, codImovel, parametros);
        parametros.add((long) (pagina - 1) * tamanho);
        parametros.add(tamanho);

        // A subconsulta pagina só os ids; a caixa envolvente é calculada depois, só para as linhas
        // da página, e não para a tabela inteira.
        String sql = """
                SELECT i.cod_imovel, i.municipio, i.area_ha, i.situacao,
                       SDO_GEOM.SDO_MIN_MBR_ORDINATE(i.geometria, 1) AS min_lon,
                       SDO_GEOM.SDO_MIN_MBR_ORDINATE(i.geometria, 2) AS min_lat,
                       SDO_GEOM.SDO_MAX_MBR_ORDINATE(i.geometria, 1) AS max_lon,
                       SDO_GEOM.SDO_MAX_MBR_ORDINATE(i.geometria, 2) AS max_lat
                  FROM (SELECT id, cod_imovel FROM imovel_rural %s
                         ORDER BY cod_imovel
                        OFFSET ? ROWS FETCH NEXT ? ROWS ONLY) p
                  JOIN imovel_rural i ON i.id = p.id
                 ORDER BY p.cod_imovel
                """.formatted(where);

        return jdbc.query(sql, (rs, n) -> new ImovelResumoResponse(
                rs.getString("cod_imovel"),
                rs.getString("municipio"),
                rs.getBigDecimal("area_ha"),
                rs.getString("situacao"),
                rs.getObject("min_lon", Double.class),
                rs.getObject("min_lat", Double.class),
                rs.getObject("max_lon", Double.class),
                rs.getObject("max_lat", Double.class)
        ), parametros.toArray());
    }

    /** Imóvel para o mapa: atributos + geometria em WKB (a conversão para GeoJSON fica no service). */
    public record ImovelNoMapa(String codImovel, String municipio, BigDecimal areaHa,
                               String situacao, byte[] wkb) {}

    /**
     * Imóveis que tocam a caixa da tela, no máximo {@code limite} linhas. O SDO_FILTER usa só o
     * índice espacial (compara caixas envolventes), que é o bastante para desenhar e é bem rápido.
     */
    public List<ImovelNoMapa> buscarNaArea(double minLon, double minLat, double maxLon, double maxLat, int limite) {
        String sql = """
                SELECT cod_imovel, municipio, area_ha, situacao, SDO_UTIL.TO_WKBGEOMETRY(geometria) AS wkb
                  FROM imovel_rural
                 WHERE SDO_FILTER(geometria,
                         SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 3),
                                      SDO_ORDINATE_ARRAY(?, ?, ?, ?))) = 'TRUE'
                   AND ROWNUM <= ?
                """;
        return jdbc.query(sql, (rs, n) -> new ImovelNoMapa(
                rs.getString("cod_imovel"),
                rs.getString("municipio"),
                rs.getBigDecimal("area_ha"),
                rs.getString("situacao"),
                rs.getBytes("wkb")
        ), minLon, minLat, maxLon, maxLat, limite);
    }

    /** Filtros por "contém", sem diferenciar maiúsculas. Só concatena texto fixo; os valores vão como parâmetro. */
    private static String filtro(String municipio, String codImovel, List<Object> parametros) {
        List<String> condicoes = new ArrayList<>();
        if (municipio != null && !municipio.isBlank()) {
            condicoes.add("UPPER(municipio) LIKE '%' || UPPER(?) || '%'");
            parametros.add(municipio.trim());
        }
        if (codImovel != null && !codImovel.isBlank()) {
            condicoes.add("UPPER(cod_imovel) LIKE '%' || UPPER(?) || '%'");
            parametros.add(codImovel.trim());
        }
        return condicoes.isEmpty() ? "" : " WHERE " + String.join(" AND ", condicoes);
    }
}
