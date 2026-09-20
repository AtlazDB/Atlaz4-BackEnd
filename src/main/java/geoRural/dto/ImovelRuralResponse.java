package geoRural.dto;

import geoRural.entity.ImovelRural;

import java.math.BigDecimal;
import java.util.Map;

public record ImovelRuralResponse(
        String codImovel,
        String municipio,
        String estado,
        BigDecimal areaHa,
        Map<String, Object> geometria
) {
    public static ImovelRuralResponse de(ImovelRural imovel, Map<String, Object> geometria) {
        return new ImovelRuralResponse(
                imovel.getCodImovel(),
                imovel.getMunicipio(),
                imovel.getEstado(),
                imovel.getAreaHa(),
                geometria
        );
    }
}