package geoRural.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import geoRural.dto.CalculoIndicadoresResponse;
import geoRural.dto.IndicadorResponse;
import geoRural.dto.IndicadoresImovelResponse;
import geoRural.exception.ImovelNaoEncontradoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ImovelRuralRepository;
import geoRural.repository.IndicadorRepository;

@Service
public class IndicadorServiceImpl implements IndicadorService {

    static final List<String> INDICADORES_CALCULADOS = List.of("IRL", "IFC");

    private final IndicadorRepository repository;
    private final ImovelRuralRepository imovelRepository;

    public IndicadorServiceImpl(IndicadorRepository repository, ImovelRuralRepository imovelRepository) {
        this.repository = repository;
        this.imovelRepository = imovelRepository;
    }

    // Os dois na mesma transação: ou a rodada inteira fica gravada, ou nenhuma parte dela.
    @Override
    @Transactional
    public CalculoIndicadoresResponse calcular() {
        int irl = repository.calcularIrl();
        int ifc = repository.calcularIfc();
        return new CalculoIndicadoresResponse(irl, ifc, OffsetDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public IndicadoresImovelResponse buscarPorImovel(String codImovel) {
        exigirImovel(codImovel);
        return new IndicadoresImovelResponse(codImovel, repository.buscarAtuais(codImovel));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IndicadorResponse> historico(String codImovel, String indicador) {
        String sigla = indicador == null ? "" : indicador.trim().toUpperCase(Locale.ROOT);
        if (!INDICADORES_CALCULADOS.contains(sigla)) {
            throw new RequisicaoInvalidaException(
                    "Indicador inválido. Use: " + String.join(", ", INDICADORES_CALCULADOS) + ".",
                    List.of("indicador"));
        }
        exigirImovel(codImovel);
        return repository.buscarHistorico(codImovel, sigla);
    }

    private void exigirImovel(String codImovel) {
        if (!imovelRepository.existsByCodImovel(codImovel)) {
            throw new ImovelNaoEncontradoException(codImovel);
        }
    }
}
