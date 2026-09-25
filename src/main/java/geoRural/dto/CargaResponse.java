package geoRural.dto;

public record CargaResponse(
        Long arquivoBrutoId,
        Long execucaoId,
        String status,
        String conjunto,
        Integer versao,
        int registrosLidos,
        int inseridos,
        int atualizados
) {
}
