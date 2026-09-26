package geoRural.service;

import geoRural.dto.CargaResponse;

public interface CargaMunicipioService {

    CargaResponse carregar(Long arquivoBrutoId, String geoJson);

}
