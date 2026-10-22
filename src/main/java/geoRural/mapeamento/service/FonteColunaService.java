package geoRural.mapeamento.service;

import geoRural.mapeamento.dto.FonteColunaResponse;

import java.util.List;

public interface FonteColunaService {

    /**
     * Colunas detectadas da fonte, cada uma com sua situação (mapeada, nova ou ausente).
     * Lança FonteNaoEncontradaException se a fonte não existir.
     */
    List<FonteColunaResponse> listar(Long fonteId);
}