package geoRural.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.CargaResponse;
import geoRural.entity.ArquivoBruto;
import geoRural.entity.DatasetVersao;
import geoRural.entity.Execucao;
import geoRural.entity.ImovelRural;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.CargaNaoPermitidaException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.repository.ArquivoBrutoRepository;
import geoRural.repository.DatasetVersaoRepository;
import geoRural.repository.ExecucaoRepository;
import geoRural.repository.ImovelRuralRepository;
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

/**
 * Carga de imóveis rurais (GeoJSON) na zona tratada, com rastreamento no
 * arquivo_bruto e na execucao da US01.
 *
 * Cada etapa roda na SUA transação (TransactionTemplate), e o método principal
 * não tem transação nenhuma. É isso que permite gravar o FALHOU: se a carga
 * estivesse na mesma transação do rastreamento, o rollback do erro apagaria
 * também o registro de que houve erro.
 */
@Service
public class CargaServiceImpl implements CargaService {

    private static final List<String> STATUS_CARREGAVEIS =
            List.of(ArquivoBruto.STATUS_RECEBIDO, ArquivoBruto.STATUS_REJEITADO);

    private final ArquivoBrutoRepository arquivoRepository;
    private final ExecucaoRepository execucaoRepository;
    private final DatasetVersaoRepository datasetVersaoRepository;
    private final ImovelRuralRepository imovelRepository;
    private final GeoJsonService geoJsonService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transacao;

    public CargaServiceImpl(ArquivoBrutoRepository arquivoRepository,
                            ExecucaoRepository execucaoRepository,
                            DatasetVersaoRepository datasetVersaoRepository,
                            ImovelRuralRepository imovelRepository,
                            GeoJsonService geoJsonService,
                            ObjectMapper objectMapper,
                            TransactionTemplate transacao) {
        this.arquivoRepository = arquivoRepository;
        this.execucaoRepository = execucaoRepository;
        this.datasetVersaoRepository = datasetVersaoRepository;
        this.imovelRepository = imovelRepository;
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

    // Transação 2: a carga em si. Ou grava tudo (versão + imóveis + CONCLUIDA),
    // ou não grava nada.
    private CargaResponse gravar(Long execucaoId, String geoJson) {
        Execucao execucao = execucaoRepository.findById(execucaoId).orElseThrow();
        ArquivoBruto arquivo = execucao.getArquivoBruto();

        JsonNode features = lerFeatures(geoJson);

        int proximaVersao = datasetVersaoRepository.ultimaVersao(DatasetVersao.CONJUNTO_IMOVEIS_RURAIS) + 1;

        DatasetVersao versao = new DatasetVersao();
        versao.setConjunto(DatasetVersao.CONJUNTO_IMOVEIS_RURAIS);
        versao.setVersao(proximaVersao);
        versao.setExecucao(execucao);
        versao.setZona(DatasetVersao.ZONA_TRATADA);
        // Ainda não existe objeto na zona tratada do bucket: a carga vai direto
        // para o Oracle. A chave segue o padrão das versões já gravadas
        // (tratada/<sigla>/<data>/<conjunto>_v<n>) para quando existir.
        versao.setChaveObjeto(String.format("tratada/%s/%s/%s_v%d",
                arquivo.getFonte().getSigla(),
                LocalDate.now(ZoneOffset.UTC),
                DatasetVersao.CONJUNTO_IMOVEIS_RURAIS,
                proximaVersao));
        versao.setRegistros(features.size());
        versao = datasetVersaoRepository.save(versao);

        int inseridos = 0;
        int atualizados = 0;

        for (int i = 0; i < features.size(); i++) {
            JsonNode feature = features.get(i);
            JsonNode propriedades = feature.path("properties");

            String codImovel = texto(propriedades, "cod_imovel");
            if (codImovel == null) {
                throw new RequisicaoInvalidaException(
                        "Feature " + i + " sem a propriedade cod_imovel.", List.of("cod_imovel"));
            }

            Geometry geometria = lerGeometria(feature.path("geometry"), i);

            // cod_imovel é UNIQUE: imovel_rural guarda o estado atual. Imóvel que
            // já existe é atualizado e passa a apontar para a versão nova.
            ImovelRural imovel = imovelRepository.findByCodImovel(codImovel).orElse(null);
            if (imovel == null) {
                imovel = new ImovelRural();
                imovel.setCodImovel(codImovel);
                inseridos++;
            } else {
                atualizados++;
            }

            imovel.setMunicipio(texto(propriedades, "municipio"));
            imovel.setEstado(texto(propriedades, "estado", "cod_estado"));
            imovel.setAreaHa(numero(propriedades, "area_ha", "num_area"));
            imovel.setGeometria(geometria);
            imovel.setDatasetVersaoId(versao.getId());
            imovelRepository.save(imovel);
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

    // Aceita mais de um nome de propriedade: o CAR usa cod_estado/num_area.
    private static String texto(JsonNode propriedades, String... nomes) {
        for (String nome : nomes) {
            JsonNode valor = propriedades.path(nome);
            if (!valor.isMissingNode() && !valor.isNull() && !valor.asText().isBlank()) {
                return valor.asText().trim();
            }
        }
        return null;
    }

    private static BigDecimal numero(JsonNode propriedades, String... nomes) {
        String valor = texto(propriedades, nomes);
        if (valor == null) {
            return null;
        }
        try {
            return new BigDecimal(valor);
        } catch (NumberFormatException e) {
            throw new RequisicaoInvalidaException("Área inválida: " + valor, List.of(nomes[0]));
        }
    }
}
