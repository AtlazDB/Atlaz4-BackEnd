package geoRural.service;

import geoRural.dto.FonteRequest;
import geoRural.entity.Fonte;

import java.util.List;

public interface FonteService {

    List<Fonte> listar(String busca);

    Fonte cadastrar(FonteRequest request);

    /**
     * Edita uma fonte. A sigla não muda: ela é a pasta dos arquivos da fonte na zona bruta
     * (bruta/&lt;SIGLA&gt;/...). Cabeçalhos e credencial, que a API nunca devolve, ficam como
     * estão quando não vêm no pedido.
     */
    Fonte atualizar(Long id, FonteRequest request);

    Fonte buscarPorId(Long id);


}
