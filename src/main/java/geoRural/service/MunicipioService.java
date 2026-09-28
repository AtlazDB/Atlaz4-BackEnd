package geoRural.service;

import geoRural.entity.Municipio;

import java.util.List;

public interface MunicipioService {

    List<Municipio> listar(String estado);

    Municipio buscarPorCodIbge(String codIbge);

}
