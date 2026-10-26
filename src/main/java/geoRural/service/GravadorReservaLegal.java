package geoRural.service;

import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.util.List;

import org.locationtech.jts.io.WKBWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import geoRural.service.ValidadorImovel.Resultado;

/** Escrita da Reserva Legal em SQL direto, em lote, como o GravadorImoveis. */
@Component
public class GravadorReservaLegal {

    public static final String CONJUNTO = "reserva_legal";

    /** Tabela de destino gravada na quarentena (registro_quarentena.entidade). */
    public static final String ENTIDADE = "car_reserva_legal";

    private static final String SQL_INSERT = """
            INSERT INTO car_reserva_legal (cod_imovel, municipio, area_ha, situacao, geometria, dataset_versao_id)
            VALUES (?, ?, ?, ?, SDO_GEOMETRY(?, 4326), ?)
            """;

    private final JdbcTemplate jdbc;

    public GravadorReservaLegal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void gravar(long datasetVersaoId, List<Resultado> aprovados) {
        if (aprovados.isEmpty()) {
            return;
        }
        WKBWriter wkbWriter = new WKBWriter();
        jdbc.batchUpdate(SQL_INSERT, aprovados, aprovados.size(), (PreparedStatement ps, Resultado r) -> {
            byte[] wkb = wkbWriter.write(r.geometria());
            ps.setString(1, r.imovel().codImovel());
            ps.setString(2, r.imovel().municipio());
            ps.setBigDecimal(3, r.imovel().areaHa());
            ps.setString(4, r.imovel().situacao());
            ps.setBlob(5, new ByteArrayInputStream(wkb), wkb.length);
            ps.setLong(6, datasetVersaoId);
        });
    }

    /**
     * Uma carga nova substitui a RL dos imóveis que vieram nela: apaga os polígonos de versões
     * anteriores desses imóveis. Roda no fim, depois de todos os lotes — apagar lote a lote
     * apagaria polígonos do mesmo imóvel gravados num lote anterior desta mesma carga.
     */
    public int substituirAnteriores(long datasetVersaoId) {
        return jdbc.update("""
                DELETE FROM car_reserva_legal
                 WHERE dataset_versao_id <> ?
                   AND cod_imovel IN (SELECT cod_imovel FROM car_reserva_legal WHERE dataset_versao_id = ?)
                """, datasetVersaoId, datasetVersaoId);
    }
}
