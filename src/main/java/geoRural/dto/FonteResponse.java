package geoRural.dto;

import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Uma fonte com os arquivos recebidos. {@code cabecalhos} e {@code credencialRef} não vêm de
 * propósito: podem levar token de acesso, e a lista de fontes é aberta a quem usa a tela.
 *
 * {@code coletando}: há pedido de coleta na fila do Airflow, ou uma coleta começou e ainda não
 * terminou (a DAG coletar_fonte escreve o andamento na mensagem e só preenche
 * {@code ultimaColetaEm} no fim, com sucesso ou não).
 */
public record FonteResponse(
        Long id,
        String nome,
        String sigla,
        String orgao,
        String formato,
        String periodicidade,
        String status,
        OffsetDateTime criadaEm,
        List<ArquivoBrutoResponse> arquivos,
        String modalidade,
        String urlOrigem,
        String metodoHttp,
        String corpoRequisicao,
        String paginacao,
        String cron,
        boolean coletando,
        OffsetDateTime ultimaColetaEm,
        String ultimaColetaMensagem
) {
    public static FonteResponse de(Fonte f, List<ArquivoBruto> arquivos) {
        return new FonteResponse(
                f.getId(),
                f.getNome(),
                f.getSigla(),
                f.getOrgao(),
                f.getFormato(),
                f.getPeriodicidade(),
                status(f, arquivos),
                f.getCriadaEm(),
                arquivos.stream().map(ArquivoBrutoResponse::de).toList(),
                f.getModalidade(),
                f.getUrlOrigem(),
                f.getMetodoHttp(),
                f.getCorpoRequisicao(),
                f.getPaginacao(),
                f.getCron(),
                f.getColetaSolicitadaEm() != null
                        || (f.getUltimaColetaMensagem() != null && f.getUltimaColetaEm() == null),
                f.getUltimaColetaEm(),
                f.getUltimaColetaMensagem()
        );
    }

    private static String status(Fonte f, List<ArquivoBruto> arquivos) {
        if (!f.isAtiva()) return "inativa";
        return arquivos.isEmpty() ? "pendente" : "ativa";
    }
}
