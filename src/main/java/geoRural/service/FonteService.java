package geoRural.service;

import geoRural.dto.FonteRequest;
import geoRural.entity.Fonte;

import java.util.List;

public interface FonteService {

    List<Fonte> listar(String busca);

    Fonte cadastrar(FonteRequest request);

    Fonte buscarPorId(Long id);

}
