package geoRural.mapeamento.service;

import geoRural.mapeamento.entity.Dominio;

import java.util.List;

public interface DominioService {

    /** Domínios ativos, com os valores, ordenados por código. */
    List<Dominio> listar();
}