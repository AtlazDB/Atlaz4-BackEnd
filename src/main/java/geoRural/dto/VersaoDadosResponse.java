package geoRural.dto;

import java.time.OffsetDateTime;

/**
 * Uma versão de dados usada num cálculo, com o caminho até a origem: a execução que a gerou e o
 * arquivo da zona bruta (nome e hash) de onde ela saiu.
 */
public record VersaoDadosResponse(
        String conjunto,
        int versao,
        Long execucaoId,
        OffsetDateTime processadoEm,
        String fonte,
        String fonteNome,
        Long arquivoId,
        String arquivo,
        String hash
) {
}
