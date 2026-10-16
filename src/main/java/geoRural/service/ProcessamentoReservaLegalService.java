package geoRural.service;

import geoRural.dto.ProcessamentoResponse;

public interface ProcessamentoReservaLegalService {

    /** Baixa o .zip de Reserva Legal da zona bruta, valida os polígonos e grava no Oracle. */
    ProcessamentoResponse processar(Long arquivoId);

}
