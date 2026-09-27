package geoRural.dto;

import java.util.List;

/**
 * Filtros comuns à tabela ({@code /imoveis/pagina}) e ao mapa ({@code /imoveis/mapa}).
 * Campos nulos ou em branco não filtram.
 *
 * @param municipio nome EXATO do município, sem diferenciar maiúsculas nem acento
 * @param codImovel trecho do código do imóvel ("contém")
 * @param situacao  situação do cadastro no CAR: AT (ativo), CA (cancelado), PE (pendente), SU (suspenso)
 */
public record FiltroImoveis(String municipio, String codImovel, String situacao) {

    public static final List<String> SITUACOES = List.of("AT", "CA", "PE", "SU");

    public static final FiltroImoveis NENHUM = new FiltroImoveis(null, null, null);
}
