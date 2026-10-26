package geoRural.mapeamento.service;

import geoRural.mapeamento.entity.ReferenciaCruzada;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReferenciaCruzadaServiceImpl implements ReferenciaCruzadaService {

    @Override
    public List<ReferenciaCruzada> listar() {
        return List.of(ReferenciaCruzada.values());
    }
}