package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Resultado de um processamento. {@code status} é o novo status do arquivo: PROCESSADO ou REJEITADO. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessamentoResponse(
        Long arquivoId,
        String status,
        Long execucaoId,
        Integer versao,
        Integer registrosLidos,
        Integer registrosValidos,
        Integer registrosInvalidos,
        String mensagemErro
) {
    public static ProcessamentoResponse processado(long arquivoId, long execucaoId, int versao,
                                                   int lidos, int validos, int invalidos) {
        return new ProcessamentoResponse(arquivoId, "PROCESSADO", execucaoId, versao,
                lidos, validos, invalidos, null);
    }

    public static ProcessamentoResponse rejeitado(long arquivoId, Long execucaoId, String mensagem) {
        return new ProcessamentoResponse(arquivoId, "REJEITADO", execucaoId, null,
                null, null, null, mensagem);
    }
}
