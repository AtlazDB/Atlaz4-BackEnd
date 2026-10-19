package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resultado de um pedido de processamento. {@code status} é o novo status do arquivo:
 * PROCESSADO ou REJEITADO quando o back-end processa, RECEBIDO quando o processamento ficou
 * na fila do Airflow (o resultado final chega depois, pelo status do arquivo).
 */
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

    public static ProcessamentoResponse agendado(long arquivoId) {
        return new ProcessamentoResponse(arquivoId, "RECEBIDO", null, null,
                null, null, null, null);
    }
}
