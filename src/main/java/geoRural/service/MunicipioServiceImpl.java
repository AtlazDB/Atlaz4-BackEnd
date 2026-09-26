package geoRural.service;

import geoRural.entity.Municipio;
import geoRural.exception.MunicipioNaoEncontradoException;
import geoRural.repository.MunicipioRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MunicipioServiceImpl implements MunicipioService {

    private final MunicipioRepository repository;

    public MunicipioServiceImpl(MunicipioRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Municipio> listar(String estado) {
        if (estado == null || estado.isBlank()) {
            return repository.findAllByOrderByEstadoAscNomeAsc();
        }
        return repository.findByEstadoOrderByNome(estado.trim().toUpperCase());
    }

    @Override
    public Municipio buscarPorCodIbge(String codIbge) {
        return repository.findByCodIbge(codIbge)
                .orElseThrow(() -> new MunicipioNaoEncontradoException(codIbge));
    }

}
