package geoRural.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Focos de calor para o mapa, em SQL direto (mesmo filtro por caixa da consulta de imóveis). */
@Repository
public class FocoRepository {

    public record FocoNoMapa(String idInpe, LocalDateTime dataHora, String satelite, String municipio,
                             BigDecimal frp, double lon, double lat) {}

    private final JdbcTemplate jdbc;

    public FocoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FocoNoMapa> buscarNaArea(double minLon, double minLat, double maxLon, double maxLat,
                                         String satelite, LocalDateTime inicio, LocalDateTime fim, int limite) {
        return jdbc.query("""
                SELECT f.id_inpe, f.data_hora, f.satelite, f.municipio, f.frp,
                       f.geometria.sdo_point.x AS lon, f.geometria.sdo_point.y AS lat
                  FROM foco_calor f
                 WHERE SDO_FILTER(f.geometria,
                                  SDO_GEOMETRY(2003, 4326, NULL, SDO_ELEM_INFO_ARRAY(1, 1003, 3),
                                               SDO_ORDINATE_ARRAY(?, ?, ?, ?))) = 'TRUE'
                   AND f.satelite = ?
                   AND f.data_hora >= ?
                   AND f.data_hora < ?
                   AND ROWNUM <= ?
                """,
                (rs, n) -> new FocoNoMapa(
                        rs.getString("id_inpe"),
                        rs.getTimestamp("data_hora").toLocalDateTime(),
                        rs.getString("satelite"),
                        rs.getString("municipio"),
                        rs.getBigDecimal("frp"),
                        rs.getDouble("lon"),
                        rs.getDouble("lat")),
                minLon, minLat, maxLon, maxLat, satelite,
                Timestamp.valueOf(inicio), Timestamp.valueOf(fim), limite);
    }
}
