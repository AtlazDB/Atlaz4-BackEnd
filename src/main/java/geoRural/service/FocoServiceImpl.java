package geoRural.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import geoRural.dto.FocosNoMapaResponse;
import geoRural.dto.MapaImoveisResponse.Feature;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.FocoRepository;
import geoRural.repository.FocoRepository.FocoNoMapa;
import geoRural.repository.IndicadorRepository;
import geoRural.repository.IndicadorRepository.RegraVigente;

@Service
public class FocoServiceImpl implements FocoService {

    static final int LIMITE_MAXIMO = 5000;

    private final FocoRepository repository;
    private final IndicadorRepository indicadorRepository;

    public FocoServiceImpl(FocoRepository repository, IndicadorRepository indicadorRepository) {
        this.repository = repository;
        this.indicadorRepository = indicadorRepository;
    }

    @Override
    public FocosNoMapaResponse listarNoMapa(Double minLon, Double minLat, Double maxLon, Double maxLat, int limite) {
        validarArea(minLon, minLat, maxLon, maxLat, limite);

        // O mapa mostra o que a regra vigente conta: mesmo satélite, mesma janela
        RegraVigente regra = indicadorRepository.regraVigente("IFC")
                .orElseThrow(() -> new IllegalStateException("Nenhuma regra ativa para o IFC."));
        String satelite = String.valueOf(regra.parametros().get("satelite"));
        int meses = ((Number) regra.parametros().get("janela_meses")).intValue();
        LocalDateTime fim = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime inicio = fim.minusMonths(meses);

        List<FocoNoMapa> focos = repository.buscarNaArea(minLon, minLat, maxLon, maxLat,
                satelite, inicio, fim, limite + 1);
        boolean truncado = focos.size() > limite;
        if (truncado) {
            focos = focos.subList(0, limite);
        }

        List<Feature> features = focos.stream()
                .map(f -> Feature.de(propriedades(f),
                        "{\"type\":\"Point\",\"coordinates\":[" + f.lon() + "," + f.lat() + "]}"))
                .toList();
        return FocosNoMapaResponse.de(features, truncado, limite, satelite, meses, inicio, fim);
    }

    private static Map<String, Object> propriedades(FocoNoMapa f) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("idInpe", f.idInpe());
        p.put("dataHora", f.dataHora().toString());
        p.put("satelite", f.satelite());
        p.put("municipio", f.municipio());
        p.put("frp", f.frp());
        return p;
    }

    private static void validarArea(Double minLon, Double minLat, Double maxLon, Double maxLat, int limite) {
        if (minLon == null || minLat == null || maxLon == null || maxLat == null) {
            throw new RequisicaoInvalidaException("Informe a área do mapa: minLon, minLat, maxLon e maxLat.",
                    List.of("minLon", "minLat", "maxLon", "maxLat"));
        }
        if (minLon >= maxLon || minLat >= maxLat
                || minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
            throw new RequisicaoInvalidaException("Área do mapa inválida.",
                    List.of("minLon", "minLat", "maxLon", "maxLat"));
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new RequisicaoInvalidaException("limite deve estar entre 1 e " + LIMITE_MAXIMO + ".",
                    List.of("limite"));
        }
    }
}
