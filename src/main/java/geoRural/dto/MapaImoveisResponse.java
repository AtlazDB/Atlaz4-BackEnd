package geoRural.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * GeoJSON FeatureCollection pronto para o {@code L.geoJSON} do Leaflet.
 * {@code truncado} e {@code limite} são membros extras (o GeoJSON permite): quando {@code truncado}
 * é true, havia mais imóveis na área do que o limite, e o front deve pedir para aproximar o zoom.
 */
public record MapaImoveisResponse(String type, List<Feature> features, boolean truncado, int limite) {

    public static MapaImoveisResponse de(List<Feature> features, boolean truncado, int limite) {
        return new MapaImoveisResponse("FeatureCollection", features, truncado, limite);
    }

    /** {@code geometry} já é JSON pronto (vem do GeoJsonWriter do JTS); vai para a resposta sem reprocessar. */
    public record Feature(String type, Map<String, Object> properties, @JsonRawValue String geometry) {
        public static Feature de(Map<String, Object> properties, String geometry) {
            return new Feature("Feature", properties, geometry);
        }
    }
}
