package geoRural.dto;

public record QuarentenaFiltroDTO(
    String tipoErro,
    Long fonteId,
    String status,
    String texto
) {}