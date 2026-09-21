package geoRural.service;

import geoRural.exception.ImovelNaoEncontradoException;
import geoRural.entity.ImovelRural;
import geoRural.repository.ImovelRuralRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImovelRuralServiceImpl implements ImovelRuralService {

    private final ImovelRuralRepository repository;

    public ImovelRuralServiceImpl(ImovelRuralRepository repository) {
        this.repository = repository;
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

}