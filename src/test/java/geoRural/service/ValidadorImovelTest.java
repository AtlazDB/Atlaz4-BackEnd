package geoRural.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void gravataBorboletaVaiParaQuarentenaSemSerCorrigida() throws ParseException {
        // Anel que se cruza no meio, em (1, 1). Antes o GeometryFixer consertava sem registro;
        // agora vai para a quarentena dizendo o erro e onde ele está.
        var resultado = validador.validar(imovel("PR-1", "POLYGON ((0 0, 2 2, 2 0, 0 2, 0 0))"));

        assertFalse(resultado.aprovado());
        assertNull(resultado.geometria());
        assertEquals("geometria inválida: Self-intersection em (1.0, 1.0)", resultado.motivoQuarentena());
    }

    @Test
    void poligonoDegeneradoVaiParaQuarentena() throws ParseException {
        // Pontos em linha reta: área zero, não é um polígono válido
        var resultado = validador.validar(imovel("PR-1", "POLYGON ((0 0, 1 1, 2 2, 0 0))"));

        assertFalse(resultado.aprovado());
        assertTrue(resultado.motivoQuarentena().startsWith("geometria inválida: "));
    }

    @Test
    void geometriaQueNaoEPoligonoVaiParaQuarentena() throws ParseException {
        assertEquals("geometria não é polígono: LineString",
                validador.validar(imovel("PR-1", "LINESTRING (0 0, 1 1)")).motivoQuarentena());
    }

    @Test
    void multiPoligonoValidoEAprovado() throws ParseException {
        var resultado = validador.validar(imovel("PR-1",
                "MULTIPOLYGON (((0 0, 0 1, 1 1, 1 0, 0 0)), ((2 2, 2 3, 3 3, 3 2, 2 2)))"));

        assertTrue(resultado.aprovado());
        assertInstanceOf(MultiPolygon.class, resultado.geometria());
    }

    @Test
    void naoAlteraAGeometriaOriginalDoImovel() throws ParseException {
        var imovel = imovel("PR-1", QUADRADO_HORARIO);
        var original = imovel.geometria().copy();

        validador.validar(imovel);

        // normalize() e reverse() trabalham na cópia: o ImovelLido continua como veio do arquivo
        assertTrue(original.equalsExact(imovel.geometria()));
    }

    private static ImovelLido imovel(String cod, String wkt) throws ParseException {
        Geometry g = wkt == null ? null : new WKTReader().read(wkt);
        return new ImovelLido(1, cod, "Londrina", null, "AT", g, Map.of());
    }
}
