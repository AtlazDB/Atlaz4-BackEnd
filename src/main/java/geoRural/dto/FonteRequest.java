package geoRural.dto;

public record FonteRequest(
        String nome,
        String sigla,
        String orgao,
        String formato,
        String periodicidade,
        String modalidade,
        String urlOrigem,
        String metodoHttp,
        String cabecalhos,
        String credencialRef,
        String corpoRequisicao,
        String paginacao,
        String cron
) {
}