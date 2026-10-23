package geoRural.mapeamento.dto;

/**
 * Corpo do POST /fontes/{id}/mapeamentos.
 * colunaId é o id de uma coluna de GET /fontes/{id}/colunas.
 * Os flags são opcionais: ausente ou null vale false.
 */
public record MapeamentoRequest(
        Long colunaId,
        String destinoTabela,
        String campoDestino,
        String tipoDado,
        String formatoOrigem,
        Boolean obrigatorio,
        Boolean chave,
        Boolean bloquearRegressao,
        Boolean validarCpfCnpj,
        String politicaGeometria,
        Boolean recortarPr,
        Long dominioId,
        String referenciaCruzada
) {
}