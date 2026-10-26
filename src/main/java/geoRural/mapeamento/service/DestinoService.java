package geoRural.mapeamento.service;

import geoRural.mapeamento.dto.DestinoResponse;

import java.util.List;

/** As tabelas da Zona Tratada que o mapeamento pode preencher, e os campos de cada uma. */
public interface DestinoService {

    List<DestinoResponse> listar();

    /**
     * Recusa (400) tabela que não é destino de mapeamento ou campo que ela não tem: sem isso o
     * erro só aparecia no processamento, como "tabela ou coluna inexistente" do Oracle.
     */
    void validar(String tabela, String campo);
}
