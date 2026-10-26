package geoRural.mapeamento.dto;

import geoRural.mapeamento.entity.FonteColuna;
import geoRural.mapeamento.entity.RegraCampo;
import geoRural.mapeamento.entity.SituacaoColuna;
import geoRural.mapeamento.util.ExemplosJson;

import java.util.List;

/**
 * Coluna detectada de uma fonte.
 * situacao: "mapeada", "nova" ou "ausente" (ver SituacaoColuna).
 * regraId: id da regra em regra_campo quando existe mapeamento (mesmo se a coluna estiver ausente); senão null.
 */
public record FonteColunaResponse(
        Long id,
        String nome,
        String tipo,
        List<String> exemplos,
        String situacao,
        Long regraId
) {
    public static FonteColunaResponse de(FonteColuna coluna, RegraCampo regra) {
        SituacaoColuna situacao = SituacaoColuna.de(coluna.isPresenteUltimoArquivo(), regra != null);
        return new FonteColunaResponse(
                coluna.getId(),
                coluna.getNomeColuna(),
                coluna.getTipoDetectado(),
                ExemplosJson.paraLista(coluna.getExemplos()),
                situacao.getCodigo(),
                regra == null ? null : regra.getId()
        );
    }
}