package geoRural.dto;

import java.util.List;

/** O valor mais recente de cada indicador do imóvel. Lista vazia: ainda não calculado. */
public record IndicadoresImovelResponse(String codImovel, List<IndicadorResponse> indicadores) {
}
