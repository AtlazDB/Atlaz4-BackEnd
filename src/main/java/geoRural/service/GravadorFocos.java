package geoRural.service;

import java.io.ByteArrayInputStream;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKBWriter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import geoRural.dto.FocoLido;
import geoRural.service.ValidadorFoco.FocoValido;
import geoRural.service.ValidadorFoco.Resultado;

/** Escrita dos focos em SQL direto, em lote. */
@Component
public class GravadorFocos {

    public static final String CONJUNTO = "focos_calor";

    public static final String ENTIDADE = "foco_calor";

    private static final GeometryFactory GEOMETRIAS = new GeometryFactory(new PrecisionModel(), ValidadorImovel.SRID);

    // Só insere o que ainda não existe: recarregar o mesmo mês (ou um diário que já veio no
    // mensal) não duplica foco — e foco duplicado inflaria o IFC.
    private static final String SQL_MERGE = """
            MERGE INTO foco_calor d
            USING (SELECT ? id_inpe, ? data_hora, ? satelite, ? cod_ibge, ? municipio, ? bioma, ? frp,
                          ? lon, ? lat, ? dataset_versao_id FROM dual) s
            ON (d.id_inpe = s.id_inpe)
            WHEN NOT MATCHED THEN INSERT
                 (id_inpe, data_hora, satelite, cod_ibge, municipio, bioma, frp, geometria, dataset_versao_id)
            VALUES (s.id_inpe, s.data_hora, s.satelite, s.cod_ibge, s.municipio, s.bioma, s.frp,
                    SDO_GEOMETRY(2001, 4326, SDO_POINT_TYPE(s.lon, s.lat, NULL), NULL, NULL),
                    s.dataset_versao_id)
            """;

    private static final String SQL_QUARENTENA = """
            INSERT INTO registro_quarentena (execucao_id, numero_linha, campo, motivo, payload,
                   entidade, chave_natural, tipo_erro, valor_recebido, dataset_versao_id, geometria)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, pkg_quality.to_geometry(?))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GravadorFocos(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void gravar(long datasetVersaoId, List<Resultado> aprovados) {
        if (aprovados.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL_MERGE, aprovados, aprovados.size(), (PreparedStatement ps, Resultado r) -> {
            FocoValido f = r.valido();
            ps.setString(1, f.idInpe());
            ps.setTimestamp(2, Timestamp.valueOf(f.dataHora()));
            ps.setString(3, f.satelite());
            ps.setString(4, f.codIbge());
            ps.setString(5, f.municipio());
            ps.setString(6, f.bioma());
            if (f.frp() == null) {
                ps.setNull(7, Types.NUMERIC);
            } else {
                ps.setBigDecimal(7, f.frp());
            }
            ps.setDouble(8, f.lon());
            ps.setDouble(9, f.lat());
            ps.setLong(10, datasetVersaoId);
        });
    }

    public void gravarQuarentena(long execucaoId, long datasetVersaoId, List<Resultado> reprovados) {
        if (reprovados.isEmpty()) {
            return;
        }
        WKBWriter wkbWriter = new WKBWriter();
        jdbc.batchUpdate(SQL_QUARENTENA, reprovados, reprovados.size(), (PreparedStatement ps, Resultado r) -> {
            String motivo = r.motivoQuarentena();
            FocoLido foco = r.foco();
            ps.setLong(1, execucaoId);
            ps.setInt(2, foco.linha());
            ps.setString(3, campo(motivo));
            ps.setString(4, motivo);
            ps.setString(5, json(r));
            ps.setString(6, ENTIDADE);
            ps.setString(7, vazio(foco.idInpe()) ? null : limitar(foco.idInpe().trim(), 100));
            ps.setString(8, tipoErro(motivo));
            ps.setString(9, limitar(valorRecebido(motivo, foco), 4000));
            ps.setLong(10, datasetVersaoId);
            Point ponto = ponto(foco);
            if (ponto == null) {
                ps.setNull(11, Types.BLOB);
            } else {
                byte[] wkb = wkbWriter.write(ponto);
                ps.setBlob(11, new ByteArrayInputStream(wkb), wkb.length);
            }
        });
    }

    /** Quantos focos esta versão trouxe de novo — os que já existiam continuam na versão de origem. */
    public int contarNovos(long datasetVersaoId) {
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM foco_calor WHERE dataset_versao_id = ?", Integer.class, datasetVersaoId);
        return total == null ? 0 : total;
    }

    static String campo(String motivo) {
        if (motivo.startsWith("id")) {
            return "id";
        }
        if (motivo.startsWith("satelite")) {
            return "satelite";
        }
        if (motivo.startsWith("data_hora")) {
            return "data_hora_gmt";
        }
        return "geometria";
    }

    /** Código da tabela tipo_erro (V10) correspondente ao motivo do ValidadorFoco. */
    static String tipoErro(String motivo) {
        if (motivo.startsWith("id vazio") || motivo.startsWith("satelite vazio")) {
            return "CAMPO_OBRIGATORIO_AUSENTE";
        }
        if (motivo.startsWith("coordenada fora")) {
            return "FORA_DO_RECORTE_TERRITORIAL";
        }
        if (motivo.startsWith("coordenada")) {
            return "GEOMETRIA_MALFORMADA";
        }
        if (motivo.startsWith("data_hora")) {
            return "TIPO_DE_DADO_INVALIDO";
        }
        return null;
    }

    static String valorRecebido(String motivo, FocoLido foco) {
        if (motivo.startsWith("coordenada")) {
            return "lat=" + foco.lat() + ", lon=" + foco.lon();
        }
        if (motivo.startsWith("data_hora")) {
            return foco.dataHora();
        }
        return null;
    }

    static Point ponto(FocoLido foco) {
        try {
            double lat = Double.parseDouble(foco.lat().trim());
            double lon = Double.parseDouble(foco.lon().trim());
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                return null;
            }
            return GEOMETRIAS.createPoint(new Coordinate(lon, lat));
        } catch (NullPointerException | NumberFormatException e) {
            return null;
        }
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }

    private static String limitar(String valor, int tamanho) {
        return valor == null || valor.length() <= tamanho ? valor : valor.substring(0, tamanho);
    }

    private String json(Resultado r) {
        try {
            return objectMapper.writeValueAsString(r.foco().atributos());
        } catch (JsonProcessingException e) {
            return String.valueOf(r.foco().atributos());
        }
    }
}
