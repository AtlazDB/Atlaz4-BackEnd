package geoRural.service;

import geoRural.dto.FocosNoMapaResponse;

public interface FocoService {

    /** Focos da área visível que entram no IFC (satélite e janela da regra vigente). */
    FocosNoMapaResponse listarNoMapa(Double minLon, Double minLat, Double maxLon, Double maxLat, int limite);

}
