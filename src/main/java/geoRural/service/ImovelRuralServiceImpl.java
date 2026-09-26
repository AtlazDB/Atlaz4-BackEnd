package geoRural.service;

import geoRural.exception.ImovelNaoEncontradoException;
import geoRural.entity.ImovelRural;
import geoRural.entity.Municipio;
import geoRural.repository.ImovelRuralRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImovelRuralServiceImpl implements ImovelRuralService {

    private final ImovelRuralRepository repository;
    private final MunicipioService municipioService;

    public ImovelRuralServiceImpl(ImovelRuralRepository repository,
                                  MunicipioService municipioService) {
        this.repository = repository;
        this.municipioService = municipioService;
    }

    @Override
    public List<ImovelRural> listarTodos() {
        return repository.findAll();
    }

    @Override
    public ImovelRural buscarPorCodImovel(String codImovel) {
        return repository.findByCodImovel(codImovel)
                .orElseThrow(() -> new ImovelNaoEncontradoException(codImovel));
    }

    @Override
    public List<ImovelRural> listarPorMunicipio(String municipio) {
        return repository.findByMunicipio(municipio);
    }

    @Override
    public List<ImovelRural> listarPorEstado(String estado) {
        return repository.findByEstado(estado.trim().toUpperCase());
    }

    // Filtra pelo vínculo espacial (municipio_id), não pelo nome em texto que a
    // fonte mandou. Município inexistente vira 404, e não lista vazia.
    @Override
    public List<ImovelRural> listarPorCodIbge(String codIbge) {
        Municipio municipio = municipioService.buscarPorCodIbge(codIbge);
        return repository.findByMunicipioId(municipio.getId());
    }

}