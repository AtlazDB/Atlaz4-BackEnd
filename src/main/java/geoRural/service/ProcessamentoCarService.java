package geoRural.service;

import geoRural.dto.ProcessamentoResponse;

public interface ProcessamentoCarService {

    /** Baixa o .zip da zona bruta, valida os imóveis e grava no Oracle. */
    ProcessamentoResponse processar(Long arquivoId);

}
