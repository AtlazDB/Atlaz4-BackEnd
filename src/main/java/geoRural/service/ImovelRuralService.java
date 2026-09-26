package geoRural.service;

import geoRural.entity.ImovelRural;

import java.util.List;

public interface ImovelRuralService {

    List<ImovelRural> listarTodos();

    ImovelRural buscarPorCodImovel(String codImovel);

    List<ImovelRural> listarPorMunicipio(String municipio);

    List<ImovelRural> listarPorEstado(String estado);

    List<ImovelRural> listarPorCodIbge(String codIbge);

}