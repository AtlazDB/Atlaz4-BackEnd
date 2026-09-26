package geoRural.dto;

import geoRural.entity.Municipio;

import java.math.BigDecimal;
import java.util.Map;

public record MunicipioResponse(
        String codIbge,
        String nome,
        String estado,
        String regiao,
        BigDecimal areaKm2,
        Map<String, Object> geometria
) {
    public static MunicipioResponse de(Municipio municipio, Map<String, Object> geometria) {
        return new MunicipioResponse(
                municipio.getCodIbge(),
                municipio.getNome(),
                municipio.getEstado(),
                municipio.getRegiao(),
                municipio.getAreaKm2(),
                geometria
        );
    }
}
