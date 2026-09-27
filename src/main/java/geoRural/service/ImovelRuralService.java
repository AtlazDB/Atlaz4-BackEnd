package geoRural.service;

import geoRural.dto.ImovelResumoResponse;
import geoRural.dto.MapaImoveisResponse;
import geoRural.dto.PaginaResponse;
import geoRural.entity.ImovelRural;

import java.util.List;

public interface ImovelRuralService {

    List<ImovelRural> listarTodos();

    ImovelRural buscarPorCodImovel(String codImovel);

    List<ImovelRural> listarPorMunicipio(String municipio);

    List<ImovelRural> listarPorEstado(String estado);

    List<ImovelRural> listarPorCodIbge(String codIbge);

    PaginaResponse<ImovelResumoResponse> listarPagina(String municipio, String codImovel, int pagina, int tamanho);

    /** Imóveis da área visível no mapa, em GeoJSON, com no máximo {@code limite} itens. */
    MapaImoveisResponse listarNoMapa(Double minLon, Double minLat, Double maxLon, Double maxLat, int limite);

}
