package geoRural.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GeoJsonService {

    // Mesmo SRID das colunas SDO_GEOMETRY (V2 e V4): WGS84 lat/long.
    public static final int SRID_WGS84 = 4326;

    private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(), SRID_WGS84);
    private final GeoJsonWriter writer = new GeoJsonWriter();
    private final GeoJsonReader reader = new GeoJsonReader(factory);
    private final ObjectMapper objectMapper;

    public GeoJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> converter(Geometry geometria) {
        if (geometria == null) {
            return null;
        }

        try {
            String geoJson = writer.write(geometria);

            return objectMapper.readValue(
                    geoJson,
                    new TypeReference<Map<String, Object>>() {}
            );

        } catch (Exception e) {
            throw new RuntimeException(
                    "Erro ao converter geometria para GeoJSON",
                    e
            );
        }
    }

    /**
     * Converte o objeto "geometry" de um GeoJSON em Geometry (JTS), com SRID 4326
     * e anéis no sentido que o Oracle espera.
     */
    public Geometry ler(String geometriaJson) throws ParseException {
        Geometry geometria = orientarAneis(reader.read(geometriaJson));
        geometria.setSRID(SRID_WGS84);
        return geometria;
    }

    // Em SRID geodésico o Oracle decide o "dentro" pelo sentido do anel: externo
    // anti-horário, buracos horário. O GeoJSON (RFC 7946) recomenda o mesmo, mas
    // não obriga — ver V6__conserta_anel_teste_001.sql.
    private Geometry orientarAneis(Geometry geometria) {
        if (geometria instanceof Polygon poligono) {
            return orientar(poligono);
        }
        if (geometria instanceof MultiPolygon multi) {
            Polygon[] partes = new Polygon[multi.getNumGeometries()];
            for (int i = 0; i < partes.length; i++) {
                partes[i] = orientar((Polygon) multi.getGeometryN(i));
            }
            return factory.createMultiPolygon(partes);
        }
        return geometria;
    }

    private Polygon orientar(Polygon poligono) {
        LinearRing externo = poligono.getExteriorRing();
        if (!Orientation.isCCW(externo.getCoordinates())) {
            externo = externo.reverse();
        }

        LinearRing[] buracos = new LinearRing[poligono.getNumInteriorRing()];
        for (int i = 0; i < buracos.length; i++) {
            LinearRing buraco = poligono.getInteriorRingN(i);
            buracos[i] = Orientation.isCCW(buraco.getCoordinates()) ? buraco.reverse() : buraco;
        }

        return factory.createPolygon(externo, buracos);
    }
}
