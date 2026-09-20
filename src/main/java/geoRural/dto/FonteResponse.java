package geoRural.dto;

import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;

import java.time.OffsetDateTime;
import java.util.List;

public record FonteResponse(
        Long id,
        String nome,
        String sigla,
        String orgao,
        String formato,
        String periodicidade,
        String status,
        OffsetDateTime criadaEm,
        List<ArquivoBrutoResponse> arquivos
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
                arquivos.stream().map(ArquivoBrutoResponse::de).toList()
        );
    }

    private static String status(Fonte f, List<ArquivoBruto> arquivos) {
        if (!f.isAtiva()) return "inativa";
        return arquivos.isEmpty() ? "pendente" : "ativa";
    }
}
