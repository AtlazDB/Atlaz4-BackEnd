package geoRural.mapeamento.service;

import geoRural.mapeamento.entity.Dominio;
import geoRural.mapeamento.repository.DominioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class DominioServiceImpl implements DominioService {

    private final DominioRepository repository;

    public DominioServiceImpl(DominioRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Dominio> listar() {
        return repository.listarComValores(true);
    }
}