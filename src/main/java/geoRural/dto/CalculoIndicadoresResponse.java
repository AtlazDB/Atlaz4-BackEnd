package geoRural.dto;

import java.time.OffsetDateTime;

/** Quantos indicadores cada cálculo gravou. IFC zero com IRL positivo: ainda não há focos carregados. */
public record CalculoIndicadoresResponse(int irl, int ifc, OffsetDateTime calculadoEm) {
}
