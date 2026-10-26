package geoRural.service;

import geoRural.dto.TesteLinkRequest;
import geoRural.dto.TesteLinkResponse;

public interface TesteLinkService {

    /**
     * Pede ao link uma amostra pequena (sem baixar tudo) e devolve o que ele responde: status,
     * total de registros, colunas e avisos. As mesmas travas da coleta valem aqui.
     */
    TesteLinkResponse testar(TesteLinkRequest request);
}
