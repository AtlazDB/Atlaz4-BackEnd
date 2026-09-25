package geoRural.service;

import geoRural.dto.CargaResponse;

public interface CargaService {

    CargaResponse carregar(Long arquivoBrutoId, String geoJson);

}
