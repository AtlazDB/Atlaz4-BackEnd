package geoRural.mapeamento.dto;

import geoRural.mapeamento.entity.Dominio;
import geoRural.mapeamento.entity.DominioValor;

import java.util.List;

/** Domínio com seus valores ativos, na ordem definida em dominio_valor.ordem. */
public record DominioResponse(
        Long id,
        String codigo,
        String nome,
        String descricao,
        List<Valor> valores
) {
    public static DominioResponse de(Dominio dominio) {
        return new DominioResponse(
                dominio.getId(),
                dominio.getCodigo(),
                dominio.getNome(),
                dominio.getDescricao(),
                dominio.getValores().stream()
                        .filter(DominioValor::isAtivo)
                        .map(v -> new Valor(v.getValor(), v.getDescricao()))
                        .toList()
        );
    }

    public record Valor(String valor, String descricao) {
    }
}