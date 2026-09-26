package geoRural.dto;

import geoRural.entity.Municipio;

import java.math.BigDecimal;

/**
 * Município sem geometria, para listagens. O polígono detalhado de centenas de
 * municípios deixaria a resposta pesada demais; quem precisa desenhar pede um
 * município por vez em /municipios/{codIbge}.
 */
public record MunicipioResumoResponse(
        String codIbge,
        String nome,
        String estado,
        String regiao,
        BigDecimal areaKm2
) {
    public static MunicipioResumoResponse de(Municipio municipio) {
        return new MunicipioResumoResponse(
                municipio.getCodIbge(),
                municipio.getNome(),
                municipio.getEstado(),
                municipio.getRegiao(),
                municipio.getAreaKm2()
        );
    }
}
