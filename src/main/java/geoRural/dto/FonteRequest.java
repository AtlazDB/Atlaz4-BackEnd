package geoRural.dto;

public record FonteRequest(
        String nome,
        String sigla,
        String orgao,
        String formato,
        String periodicidade
) {
}
