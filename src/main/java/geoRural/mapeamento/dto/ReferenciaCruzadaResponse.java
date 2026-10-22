package geoRural.mapeamento.dto;

import geoRural.mapeamento.entity.ReferenciaCruzada;

public record ReferenciaCruzadaResponse(String codigo, String nome) {

    public static ReferenciaCruzadaResponse de(ReferenciaCruzada referencia) {
        return new ReferenciaCruzadaResponse(referencia.getCodigo(), referencia.getNome());
    }
}