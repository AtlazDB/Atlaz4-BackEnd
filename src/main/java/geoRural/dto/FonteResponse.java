package geoRural.dto;

import geoRural.entity.Fonte;
import java.time.OffsetDateTime;

public record FonteResponse(
        Long id,
        String nome,
        String sigla,
        String orgao,
        String formato,
        String periodicidade,
        boolean ativa,
        OffsetDateTime criadaEm,
        String modalidade,
        String urlOrigem,
        String metodoHttp,
        String cabecalhos,
        String credencialRef,
        String corpoRequisicao,
        String paginacao,
        String cron
) {
    public FonteResponse(Fonte fonte) {
        this(
            fonte.getId(),
            fonte.getNome(),
            fonte.getSigla(),
            fonte.getOrgao(),
            fonte.getFormato(),
            fonte.getPeriodicidade(),
            fonte.isAtiva(),
            fonte.getCriadaEm(),
            fonte.getModalidade(),
            fonte.getUrlOrigem(),
            fonte.getMetodoHttp(),
            fonte.getCabecalhos(),
            fonte.getCredencialRef(),
            fonte.getCorpoRequisicao(),
            fonte.getPaginacao(),
            fonte.getCron()
        );
    }
}