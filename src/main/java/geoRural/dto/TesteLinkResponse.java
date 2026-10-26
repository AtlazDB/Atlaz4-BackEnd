package geoRural.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Resultado do "Testar link".
 *
 * @param tipo           SERVICO_ARCGIS, SERVICO_WFS ou ARQUIVO (como o link foi entendido)
 * @param statusHttp     o que o servidor respondeu à amostra
 * @param contentType    o tipo do conteúdo da amostra
 * @param total          registros no serviço; nulo quando não dá para saber (arquivo, ou o serviço não informa)
 * @param limitePorPedido quantos registros o serviço entrega por pedido (ArcGIS); nulo quando não informa
 * @param colunas        as colunas encontradas na amostra (vazio quando o arquivo é compactado)
 * @param avisos         o que o operador precisa saber antes de coletar
 * @param mensagem       o resumo para mostrar na tela
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TesteLinkResponse(
        String tipo,
        int statusHttp,
        String contentType,
        Long total,
        Integer limitePorPedido,
        List<String> colunas,
        List<String> avisos,
        String mensagem
) {
}
