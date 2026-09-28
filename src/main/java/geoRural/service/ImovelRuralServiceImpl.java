package geoRural.service;

import geoRural.dto.FiltroImoveis;
import geoRural.dto.ImovelResumoResponse;
import geoRural.dto.MapaImoveisResponse;
import geoRural.dto.MapaImoveisResponse.Feature;
import geoRural.dto.PaginaResponse;
import geoRural.exception.ImovelNaoEncontradoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.entity.ImovelRural;
import geoRural.entity.Municipio;
import geoRural.repository.ImovelRuralConsultaRepository;
import geoRural.repository.ImovelRuralConsultaRepository.ImovelNoMapa;
import geoRural.repository.ImovelRuralRepository;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImovelRuralServiceImpl implements ImovelRuralService {

    static final int TAMANHO_MAXIMO = 500;
    static final int LIMITE_MAPA_MAXIMO = 3000;

    /** 6 casas decimais em graus ≈ 10 cm: mais que isso só aumenta o JSON. */
    private static final int CASAS_DECIMAIS = 6;

    /** Largura aproximada da tela em pixels: a simplificação descarta detalhes menores que 1 pixel. */
    private static final double PIXELS_NA_LARGURA = 2000;

    private final ImovelRuralRepository repository;
    private final MunicipioService municipioService;
    private final ImovelRuralConsultaRepository consultaRepository;

    public ImovelRuralServiceImpl(ImovelRuralRepository repository,
                                  MunicipioService municipioService,
                                  ImovelRuralConsultaRepository consultaRepository) {
        this.repository = repository;
        this.municipioService = municipioService;
        this.consultaRepository = consultaRepository;
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

    @Override
    public PaginaResponse<ImovelResumoResponse> listarPagina(FiltroImoveis filtro, int pagina, int tamanho) {
        if (pagina < 1 || tamanho < 1 || tamanho > TAMANHO_MAXIMO) {
            throw new RequisicaoInvalidaException(
                    "pagina deve ser >= 1 e tamanho entre 1 e " + TAMANHO_MAXIMO + ".",
                    List.of("pagina", "tamanho"));
        }
        validarSituacao(filtro);
        long total = consultaRepository.contar(filtro);
        List<ImovelResumoResponse> itens = total == 0
                ? List.of()
                : consultaRepository.buscarPagina(filtro, pagina, tamanho);
        return new PaginaResponse<>(itens, total, pagina, tamanho);
    }

    @Override
    public MapaImoveisResponse listarNoMapa(Double minLon, Double minLat, Double maxLon, Double maxLat,
                                            FiltroImoveis filtro, int limite) {
        validarArea(minLon, minLat, maxLon, maxLat, limite);
        validarSituacao(filtro);

        // Pede um a mais que o limite só para saber se a área tinha mais imóveis do que cabem
        List<ImovelNoMapa> imoveis = consultaRepository.buscarNaArea(minLon, minLat, maxLon, maxLat, filtro, limite + 1);
        boolean truncado = imoveis.size() > limite;
        if (truncado) {
            imoveis = imoveis.subList(0, limite);
        }

        double tolerancia = (maxLon - minLon) / PIXELS_NA_LARGURA;
        WKBReader leitor = new WKBReader();
        GeoJsonWriter escritor = new GeoJsonWriter(CASAS_DECIMAIS);
        escritor.setEncodeCRS(false);

        List<Feature> features = new ArrayList<>(imoveis.size());
        for (ImovelNoMapa imovel : imoveis) {
            Geometry geometria = simplificar(ler(leitor, imovel), tolerancia);
            features.add(Feature.de(propriedades(imovel), escritor.write(geometria)));
        }
        return MapaImoveisResponse.de(features, truncado, limite);
    }

    private static void validarSituacao(FiltroImoveis filtro) {
        String situacao = filtro.situacao();
        if (situacao != null && !situacao.isBlank()
                && !FiltroImoveis.SITUACOES.contains(situacao.trim().toUpperCase())) {
            throw new RequisicaoInvalidaException(
                    "Situação inválida. Use: " + String.join(", ", FiltroImoveis.SITUACOES) + ".",
                    List.of("situacao"));
        }
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
        if (limite < 1 || limite > LIMITE_MAPA_MAXIMO) {
            throw new RequisicaoInvalidaException("limite deve estar entre 1 e " + LIMITE_MAPA_MAXIMO + ".",
                    List.of("limite"));
        }
    }

    private static Geometry ler(WKBReader leitor, ImovelNoMapa imovel) {
        try {
            return leitor.read(imovel.wkb());
        } catch (ParseException e) {
            throw new IllegalStateException("Geometria ilegível no imóvel " + imovel.codImovel(), e);
        }
    }

    /** Com pouco zoom, detalhes menores que um pixel não aparecem e só pesam no JSON. */
    private static Geometry simplificar(Geometry geometria, double tolerancia) {
        Geometry simplificada = DouglasPeuckerSimplifier.simplify(geometria, tolerancia);
        // Imóvel pequeno demais para o zoom pode sumir na simplificação: aí vai o original
        return simplificada.isEmpty() ? geometria : simplificada;
    }

    private static Map<String, Object> propriedades(ImovelNoMapa imovel) {
        Map<String, Object> propriedades = new LinkedHashMap<>();
        propriedades.put("codImovel", imovel.codImovel());
        propriedades.put("municipio", imovel.municipio());
        propriedades.put("areaHa", imovel.areaHa());
        propriedades.put("situacao", imovel.situacao());
        return propriedades;
    }
}
