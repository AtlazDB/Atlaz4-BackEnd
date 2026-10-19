package geoRural.service;

import geoRural.dto.ProcessamentoResponse;

public interface ProcessamentoFocosService {

    /** Baixa o CSV de focos do INPE da zona bruta, fica só com o Paraná e grava no Oracle. */
    ProcessamentoResponse processar(Long arquivoId);

}
