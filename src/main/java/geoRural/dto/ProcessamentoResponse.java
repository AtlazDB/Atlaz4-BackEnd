package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resultado de um pedido de processamento. {@code status} é o novo status do arquivo:
 * PROCESSADO ou REJEITADO quando o back-end processa, RECEBIDO quando o processamento ficou
 * na fila do Airflow (o resultado final chega depois, pelo status do arquivo).
 * {@code registrosForaDoRecorte} só vem nos focos do INPE, cujo arquivo traz o Brasil inteiro.
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
        Integer registrosForaDoRecorte,
        String mensagemErro
) {
    public static ProcessamentoResponse processado(long arquivoId, long execucaoId, int versao,
                                                   int lidos, int validos, int invalidos) {
        return new ProcessamentoResponse(arquivoId, "PROCESSADO", execucaoId, versao,
                lidos, validos, invalidos, null, null);
    }

    public static ProcessamentoResponse processado(long arquivoId, long execucaoId, int versao,
                                                   int lidos, int validos, int invalidos, int foraDoRecorte) {
        return new ProcessamentoResponse(arquivoId, "PROCESSADO", execucaoId, versao,
                lidos, validos, invalidos, foraDoRecorte, null);
    }

    public static ProcessamentoResponse rejeitado(long arquivoId, Long execucaoId, String mensagem) {
        return new ProcessamentoResponse(arquivoId, "REJEITADO", execucaoId, null,
                null, null, null, null, mensagem);
    }

    public static ProcessamentoResponse agendado(long arquivoId) {
        return new ProcessamentoResponse(arquivoId, "RECEBIDO", null, null,
                null, null, null, null, null);
    }
}
