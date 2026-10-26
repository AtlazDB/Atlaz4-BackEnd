package geoRural.service;

import geoRural.dto.CalculoIndicadoresResponse;
import geoRural.dto.IndicadorResponse;
import geoRural.dto.IndicadoresImovelResponse;

import java.util.List;

public interface IndicadorService {

    /** Recalcula IRL e IFC de todos os imóveis com dados para isso. */
    CalculoIndicadoresResponse calcular();

    IndicadoresImovelResponse buscarPorImovel(String codImovel);

    List<IndicadorResponse> historico(String codImovel, String indicador);

}
