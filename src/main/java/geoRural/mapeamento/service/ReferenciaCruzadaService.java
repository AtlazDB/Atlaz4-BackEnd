package geoRural.mapeamento.service;

import geoRural.mapeamento.entity.ReferenciaCruzada;

import java.util.List;

public interface ReferenciaCruzadaService {

    /** Lista fixa de tabelas que a integridade cruzada pode referenciar. */
    List<ReferenciaCruzada> listar();
}