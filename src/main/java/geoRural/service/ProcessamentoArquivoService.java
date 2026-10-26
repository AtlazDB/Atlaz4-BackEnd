package geoRural.service;

import geoRural.dto.ProcessamentoResponse;

public interface ProcessamentoArquivoService {

    /** Processa um arquivo da zona bruta com o leitor da fonte dele (CAR, CAR-RL ou INPE-FOCOS). */
    ProcessamentoResponse processar(Long arquivoId);

}
