package geoRural.service;

import geoRural.dto.ColetaResponse;

public interface ColetaLinkService {

    /**
     * Baixa o link da fonte para a zona bruta e registra o arquivo como RECEBIDO; daí em diante
     * ele segue o mesmo caminho de um upload. Conteúdo igual ao da última coleta não é gravado.
     */
    ColetaResponse executar(Long fonteId);
}
