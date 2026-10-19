package geoRural.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Focos da área visível em GeoJSON, só os que entram no IFC: o satélite e a janela da regra
 * vigente vêm junto, para a legenda do mapa dizer exatamente o que está desenhado.
 */
public record FocosNoMapaResponse(
        String type,
        List<MapaImoveisResponse.Feature> features,
        boolean truncado,
        int limite,
        String satelite,
        int janelaMeses,
        LocalDateTime inicio,
        LocalDateTime fim
) {
    public static FocosNoMapaResponse de(List<MapaImoveisResponse.Feature> features, boolean truncado, int limite,
                                         String satelite, int janelaMeses, LocalDateTime inicio, LocalDateTime fim) {
        return new FocosNoMapaResponse("FeatureCollection", features, truncado, limite,
                satelite, janelaMeses, inicio, fim);
    }
}
