package geoRural.dto;

import java.math.BigDecimal;

/** Linha da tabela de imóveis: sem geometria, só a caixa envolvente para o front dar zoom. */
public record ImovelResumoResponse(
        String codImovel,
        String municipio,
        BigDecimal areaHa,
        String situacao,
        Double minLon,
        Double minLat,
        Double maxLon,
        Double maxLat
) {}
