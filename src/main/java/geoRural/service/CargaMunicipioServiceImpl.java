package geoRural.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.CargaResponse;
import geoRural.entity.ArquivoBruto;
import geoRural.entity.DatasetVersao;
import geoRural.entity.Execucao;
import geoRural.entity.Municipio;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.CargaNaoPermitidaException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ArquivoBrutoRepository;
import geoRural.repository.DatasetVersaoRepository;
import geoRural.repository.ExecucaoRepository;
import geoRural.repository.MunicipioRepository;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Carga da malha municipal do IBGE (GeoJSON) na zona tratada, com rastreamento
 * no arquivo_bruto e na execucao — mesmo desenho da carga de imóveis rurais.
 *
 * Cada etapa roda na SUA transação (TransactionTemplate), e o método principal
 * não tem transação nenhuma. É isso que permite gravar o FALHOU: se a carga
 * estivesse na mesma transação do rastreamento, o rollback do erro apagaria
 * também o registro de que houve erro.
 */
@Service
public class CargaMunicipioServiceImpl implements CargaMunicipioService {

    private static final List<String> STATUS_CARREGAVEIS =
            List.of(ArquivoBruto.STATUS_RECEBIDO, ArquivoBruto.STATUS_REJEITADO);

    // As mesmas restrições da V4 (ck_municipio_ibge e ck_municipio_uf), conferidas
    // aqui para o erro chegar em português em vez de vir como ORA-02290.
    private static final Pattern COD_IBGE = Pattern.compile("[0-9]{7}");

    // A região sai da UF, não do arquivo: os nomes dos campos da malha do IBGE
    // mudam entre edições, mas a divisão UF -> região é fixa.
    private static final Map<String, String> REGIAO_POR_UF = Map.ofEntries(
            Map.entry("AC", "Norte"), Map.entry("AP", "Norte"), Map.entry("AM", "Norte"),
            Map.entry("PA", "Norte"), Map.entry("RO", "Norte"), Map.entry("RR", "Norte"),
            Map.entry("TO", "Norte"),
            Map.entry("AL", "Nordeste"), Map.entry("BA", "Nordeste"), Map.entry("CE", "Nordeste"),
            Map.entry("MA", "Nordeste"), Map.entry("PB", "Nordeste"), Map.entry("PE", "Nordeste"),
            Map.entry("PI", "Nordeste"), Map.entry("RN", "Nordeste"), Map.entry("SE", "Nordeste"),
            Map.entry("DF", "Centro-Oeste"), Map.entry("GO", "Centro-Oeste"),
            Map.entry("MT", "Centro-Oeste"), Map.entry("MS", "Centro-Oeste"),
            Map.entry("ES", "Sudeste"), Map.entry("MG", "Sudeste"),
            Map.entry("RJ", "Sudeste"), Map.entry("SP", "Sudeste"),
            Map.entry("PR", "Sul"), Map.entry("RS", "Sul"), Map.entry("SC", "Sul"));

    private final ArquivoBrutoRepository arquivoRepository;
    private final ExecucaoRepository execucaoRepository;
    private final DatasetVersaoRepository datasetVersaoRepository;
    private final MunicipioRepository municipioRepository;
    private final GeoJsonService geoJsonService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transacao;

    public CargaMunicipioServiceImpl(ArquivoBrutoRepository arquivoRepository,
                                     ExecucaoRepository execucaoRepository,
                                     DatasetVersaoRepository datasetVersaoRepository,
                                     MunicipioRepository municipioRepository,
                                     GeoJsonService geoJsonService,
                                     ObjectMapper objectMapper,
                                     TransactionTemplate transacao) {
        this.arquivoRepository = arquivoRepository;
        this.execucaoRepository = execucaoRepository;
        this.datasetVersaoRepository = datasetVersaoRepository;
        this.municipioRepository = municipioRepository;
        this.geoJsonService = geoJsonService;
        this.objectMapper = objectMapper;
        this.transacao = transacao;
    }

    @Override
    public CargaResponse carregar(Long arquivoBrutoId, String geoJson) {
        Long execucaoId = transacao.execute(status -> iniciar(arquivoBrutoId));

        try {
            return transacao.execute(status -> gravar(execucaoId, geoJson));
        } catch (RuntimeException e) {
            transacao.executeWithoutResult(status -> falhar(execucaoId, e));
            throw e;
        }
    }

    // Transação 1: arquivo -> PROCESSANDO e execução EM_ANDAMENTO. Fica gravado
    // mesmo que a carga quebre depois.
    private Long iniciar(Long arquivoBrutoId) {
        ArquivoBruto arquivo = arquivoRepository.findById(arquivoBrutoId)
                .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoBrutoId));

        if (!STATUS_CARREGAVEIS.contains(arquivo.getStatus())) {
            throw new CargaNaoPermitidaException(arquivoBrutoId, arquivo.getStatus());
        }

        arquivo.setStatus(ArquivoBruto.STATUS_PROCESSANDO);

        Execucao execucao = new Execucao();
        execucao.setArquivoBruto(arquivo);
        execucao.setTipo(Execucao.TIPO_PADRONIZACAO);
        execucao.setStatus(Execucao.STATUS_EM_ANDAMENTO);

        return execucaoRepository.save(execucao).getId();
    }

    // Transação 2: a carga em si. Ou grava tudo (versão + municípios + CONCLUIDA),
    // ou não grava nada.
    private CargaResponse gravar(Long execucaoId, String geoJson) {
        Execucao execucao = execucaoRepository.findById(execucaoId).orElseThrow();
        ArquivoBruto arquivo = execucao.getArquivoBruto();

        JsonNode features = lerFeatures(geoJson);

        int proximaVersao = datasetVersaoRepository.ultimaVersao(DatasetVersao.CONJUNTO_MUNICIPIOS) + 1;

        DatasetVersao versao = new DatasetVersao();
        versao.setConjunto(DatasetVersao.CONJUNTO_MUNICIPIOS);
        versao.setVersao(proximaVersao);
        versao.setExecucao(execucao);
        versao.setZona(DatasetVersao.ZONA_TRATADA);
        versao.setChaveObjeto(String.format("tratada/%s/%s/%s_v%d",
                arquivo.getFonte().getSigla(),
                LocalDate.now(ZoneOffset.UTC),
                DatasetVersao.CONJUNTO_MUNICIPIOS,
                proximaVersao));
        versao.setRegistros(features.size());
        versao = datasetVersaoRepository.save(versao);

        int inseridos = 0;
        int atualizados = 0;

        for (int i = 0; i < features.size(); i++) {
            JsonNode feature = features.get(i);
            JsonNode propriedades = feature.path("properties");

            String codIbge = lerCodIbge(propriedades, i);
            String nome = lerNome(propriedades, i);
            String estado = lerEstado(propriedades, i);
            BigDecimal areaKm2 = lerAreaKm2(propriedades, i);
            Geometry geometria = lerGeometria(feature.path("geometry"), i);

            // cod_ibge é UNIQUE: municipio guarda o estado atual. Município que
            // já existe é atualizado e passa a apontar para a versão nova.
            Municipio municipio = municipioRepository.findByCodIbge(codIbge).orElse(null);
            if (municipio == null) {
                municipio = new Municipio();
                municipio.setCodIbge(codIbge);
                inseridos++;
            } else {
                atualizados++;
            }

            municipio.setNome(nome);
            municipio.setEstado(estado);
            municipio.setRegiao(REGIAO_POR_UF.get(estado));
            municipio.setAreaKm2(areaKm2);
            municipio.setGeometria(geometria);
            municipio.setDatasetVersaoId(versao.getId());
            municipioRepository.save(municipio);
        }

        OffsetDateTime agora = OffsetDateTime.now();

        execucao.setStatus(Execucao.STATUS_CONCLUIDA);
        execucao.setRegistrosLidos(features.size());
        execucao.setRegistrosValidos(features.size());
        execucao.setRegistrosInvalidos(0);
        execucao.setFinalizadaEm(agora);

        arquivo.setStatus(ArquivoBruto.STATUS_PROCESSADO);
        arquivo.setProcessadoEm(agora);

        return new CargaResponse(
                arquivo.getId(),
                execucao.getId(),
                execucao.getStatus(),
                versao.getConjunto(),
                versao.getVersao(),
                features.size(),
                inseridos,
                atualizados
        );
    }

    // Transação 3: só roda se a 2 falhou (e já foi desfeita).
    private void falhar(Long execucaoId, RuntimeException erro) {
        Execucao execucao = execucaoRepository.findById(execucaoId).orElseThrow();

        String mensagem = erro.getMessage() == null ? erro.getClass().getSimpleName() : erro.getMessage();
        execucao.setStatus(Execucao.STATUS_FALHOU);
        execucao.setMensagemErro(mensagem.length() > 4000 ? mensagem.substring(0, 4000) : mensagem);
        execucao.setFinalizadaEm(OffsetDateTime.now());

        execucao.getArquivoBruto().setStatus(ArquivoBruto.STATUS_REJEITADO);
    }

    private JsonNode lerFeatures(String geoJson) {
        JsonNode raiz;
        try {
            raiz = objectMapper.readTree(geoJson);
        } catch (JsonProcessingException e) {
            throw new RequisicaoInvalidaException("O corpo não é um JSON válido.");
        }

        if (raiz == null || !"FeatureCollection".equals(raiz.path("type").asText())) {
            throw new RequisicaoInvalidaException("O GeoJSON precisa ser uma FeatureCollection.");
        }

        JsonNode features = raiz.path("features");
        if (!features.isArray() || features.isEmpty()) {
            throw new RequisicaoInvalidaException("A FeatureCollection não tem nenhuma feature.");
        }
        return features;
    }

    // Os nomes alternativos são os do shapefile do IBGE (CD_MUN, NM_MUN, SIGLA_UF, AREA_KM2).
    private String lerCodIbge(JsonNode propriedades, int indice) {
        String valor = texto(propriedades, "cod_ibge", "CD_MUN");
        if (valor == null) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + " sem o código do município (cod_ibge ou CD_MUN).",
                    List.of("cod_ibge"));
        }
        if (!COD_IBGE.matcher(valor).matches()) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + ": código IBGE \"" + valor + "\" inválido; precisa ter 7 dígitos.",
                    List.of("cod_ibge"));
        }
        return valor;
    }

    private String lerNome(JsonNode propriedades, int indice) {
        String valor = texto(propriedades, "nome", "NM_MUN");
        if (valor == null) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + " sem o nome do município (nome ou NM_MUN).",
                    List.of("nome"));
        }
        return valor;
    }

    private String lerEstado(JsonNode propriedades, int indice) {
        String valor = texto(propriedades, "estado", "SIGLA_UF");
        if (valor == null) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + " sem a UF (estado ou SIGLA_UF).",
                    List.of("estado"));
        }
        String uf = valor.toUpperCase();
        if (!REGIAO_POR_UF.containsKey(uf)) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + ": UF \"" + valor + "\" não existe.",
                    List.of("estado"));
        }
        return uf;
    }

    // A área é opcional na V4 (area_km2 aceita NULL); quando vem, precisa ser positiva.
    private BigDecimal lerAreaKm2(JsonNode propriedades, int indice) {
        String valor = texto(propriedades, "area_km2", "AREA_KM2");
        if (valor == null) {
            return null;
        }

        BigDecimal area;
        try {
            area = new BigDecimal(valor);
        } catch (NumberFormatException e) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + ": área \"" + valor + "\" não é um número.",
                    List.of("area_km2"));
        }

        if (area.signum() <= 0) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + ": área precisa ser maior que zero, e veio " + valor + ".",
                    List.of("area_km2"));
        }
        return area;
    }

    private Geometry lerGeometria(JsonNode geometriaJson, int indice) {
        if (geometriaJson.isMissingNode() || geometriaJson.isNull()) {
            throw new RequisicaoInvalidaException("Feature " + indice + " sem geometria.", List.of("geometry"));
        }

        Geometry geometria;
        try {
            geometria = geoJsonService.ler(geometriaJson.toString());
        } catch (ParseException | IllegalArgumentException e) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + " com geometria inválida: " + e.getMessage(), List.of("geometry"));
        }

        if (!(geometria instanceof Polygon || geometria instanceof MultiPolygon)) {
            throw new RequisicaoInvalidaException(
                    "Feature " + indice + " é " + geometria.getGeometryType()
                            + "; só Polygon e MultiPolygon são aceitos.", List.of("geometry"));
        }
        return geometria;
    }

    private static String texto(JsonNode propriedades, String... nomes) {
        for (String nome : nomes) {
            JsonNode valor = propriedades.path(nome);
            if (!valor.isMissingNode() && !valor.isNull() && !valor.asText().isBlank()) {
                return valor.asText().trim();
            }
        }
        return null;
    }
}
