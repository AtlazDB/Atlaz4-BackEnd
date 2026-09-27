package geoRural.dto;

import java.util.List;

/** Página de resultados. {@code pagina} começa em 1. */
public record PaginaResponse<T>(List<T> itens, long total, int pagina, int tamanho) {}
