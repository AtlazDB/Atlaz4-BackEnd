package geoRural.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import geoRural.dto.ImovelLido;

class ValidadorImovelTest {

    // Quadrado em sentido horário, como vem do shapefile
    private static final String QUADRADO_HORARIO = "POLYGON ((-51 -23, -51 -22, -50 -22, -50 -23, -51 -23))";

    private final ValidadorImovel validador = new ValidadorImovel();

    @Test
    void aprovaEDeixaAnelExternoAntiHorario() throws ParseException {
        var resultado = validador.validar(imovel("PR-1", QUADRADO_HORARIO));

        assertTrue(resultado.aprovado());
        Polygon p = (Polygon) resultado.geometria();
        assertTrue(Orientation.isCCW(p.getExteriorRing().getCoordinates()));
        assertEquals(4326, p.getSRID());
    }

    @Test
    void codigoVazioVaiParaQuarentena() throws ParseException {
        assertEquals("cod_imovel vazio", validador.validar(imovel(" ", QUADRADO_HORARIO)).motivoQuarentena());
        assertEquals("cod_imovel vazio", validador.validar(imovel(null, QUADRADO_HORARIO)).motivoQuarentena());
    }

    @Test
    void codigoRepetidoVaiParaQuarentena() throws ParseException {
        assertTrue(validador.validar(imovel("PR-1", QUADRADO_HORARIO)).aprovado());
        assertEquals("cod_imovel duplicado", validador.validar(imovel("PR-1", QUADRADO_HORARIO)).motivoQuarentena());
    }

    @Test
    void semGeometriaVaiParaQuarentena() throws ParseException {
        assertEquals("sem geometria", validador.validar(imovel("PR-1", null)).motivoQuarentena());
        assertEquals("sem geometria", validador.validar(imovel("PR-2", "POLYGON EMPTY")).motivoQuarentena());
    }

    @Test
    void consertaGravataBorboleta() throws ParseException {
        // Anel que se cruza no meio: inválido, o GeometryFixer separa em dois triângulos
        var resultado = validador.validar(imovel("PR-1", "POLYGON ((0 0, 2 2, 2 0, 0 2, 0 0))"));

        assertTrue(resultado.aprovado());
        assertInstanceOf(MultiPolygon.class, resultado.geometria());
        assertTrue(resultado.geometria().isValid());
    }

    @Test
    void geometriaQueNaoEPoligonoVaiParaQuarentena() throws ParseException {
        assertEquals("geometria irreparável",
                validador.validar(imovel("PR-1", "LINESTRING (0 0, 1 1)")).motivoQuarentena());
        // Polígono degenerado (área zero): o conserto vira vazio
        assertEquals("geometria irreparável",
                validador.validar(imovel("PR-2", "POLYGON ((0 0, 1 1, 2 2, 0 0))")).motivoQuarentena());
    }

    private static ImovelLido imovel(String cod, String wkt) throws ParseException {
        Geometry g = wkt == null ? null : new WKTReader().read(wkt);
        return new ImovelLido(1, cod, "Londrina", null, "AT", g, Map.of());
    }
}
