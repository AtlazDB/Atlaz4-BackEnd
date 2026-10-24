package geoRural.dto;

public record QuarentenaMetricasDTO(
    long pendentes,
    long emAnalise,
    long corrigidos,
    long descartados,
    long revalidados,
    long substituidos,
    long total
) {}


