package geoRural.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GeoJsonService {

    private final GeoJsonWriter writer = new GeoJsonWriter();
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
}