package geoRural.dto;

import java.util.Map;

import java.math.BigDecimal;

public record ImovelRuralResponse(
        Long id,
        String codImovel,
        String municipio,
        String estado,
        BigDecimal areaHa,
        Map<String, Object> geometria,
        Long datasetVersaoId
) {
}