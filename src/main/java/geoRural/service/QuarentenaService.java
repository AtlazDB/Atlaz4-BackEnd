package geoRural.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import geoRural.dto.*;
import geoRural.entity.RegistroQuarentena;
import geoRural.exception.GravacaoNaoPermitidaException;
import geoRural.exception.QuarantineClosedException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.exception.ResourceNotFoundException;
import geoRural.repository.RegistroQuarentenaRepository;
import jakarta.persistence.EntityManager;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class QuarentenaService {

    // Status em que a análise já acabou: nenhuma ação muda mais o registro.
    private static final Set<String> STATUS_ENCERRADOS = Set.of("DESCARTADO", "REVALIDADO", "SUBSTITUIDO");

    private final RegistroQuarentenaRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GeoJsonService geoJsonService;
    private final EntityManager entityManager;
    private final WKTReader wktReader = new WKTReader(
            new GeometryFactory(new PrecisionModel(), GeoJsonService.SRID_WGS84));
    private final WKTWriter wktWriter = new WKTWriter();

    public QuarentenaService(RegistroQuarentenaRepository repository, JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper, GeoJsonService geoJsonService,
                             EntityManager entityManager) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.geoJsonService = geoJsonService;
        this.entityManager = entityManager;
    }

    public Page<RegistroQuarentena> listar(QuarentenaFiltroDTO filtro, Pageable pageable) {
        return repository.buscarComFiltros(
            filtro.tipoErro(),
            filtro.status(),
            filtro.texto(),
            filtro.fonteId(),
            pageable
        );
    }

    public RegistroQuarentena buscarPorId(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Registro de quarentena não encontrado: " + id));
    }

    /** A geometria como chegou, em GeoJSON, para o visualizador. Null se ela nem pôde ser lida. */
    public Map<String, Object> buscarGeometria(Long id) {
        return geoJsonService.converter(buscarPorId(id).getGeometria());
    }

    /** A geometria que o operador salvou, em GeoJSON. Null enquanto não houver correção. */
    public Map<String, Object> findCorrectedGeometry(Long id) {
        return geoJsonService.converter(buscarPorId(id).getGeometriaCorrigida());
    }

    /**
     * Sugestão da correção automática (pkg_quality.fix_geometry, o mesmo RECTIFY do validate)
     * em GeoJSON. Não grava: a tela mostra a prévia e o operador salva se concordar.
     */
    public Map<String, Object> suggestGeometryFix(Long id) {
        RegistroQuarentena registro = buscarEmAberto(id);
        if (registro.getGeometria() == null) {
            throw new RequisicaoInvalidaException(
                    "O registro não tem geometria legível para corrigir automaticamente: corrija pelo GeoJSON ou descarte com justificativa.",
                    List.of("geometria"));
        }

        Map<String, String> resultado = jdbcTemplate.queryForObject("""
                SELECT pkg_quality.validate_geometry(geometria) AS validacao,
                       SDO_UTIL.TO_WKTGEOMETRY(pkg_quality.fix_geometry(geometria)) AS corrigida
                  FROM registro_quarentena
                 WHERE id = ?""",
                (rs, n) -> Map.of("validacao", rs.getString("validacao"),
                                  "corrigida", Objects.toString(rs.getString("corrigida"), "")),
                id);

        if ("TRUE".equals(resultado.get("validacao"))) {
            throw new RequisicaoInvalidaException(
                    "A geometria original já é válida: não há o que corrigir automaticamente.", List.of("geometria"));
        }
        if (resultado.get("corrigida").isEmpty()) {
            throw new RequisicaoInvalidaException(
                    "A correção automática não conseguiu deixar a geometria válida: edite os vértices no mapa.",
                    List.of("geometria"));
        }

        try {
            return geoJsonService.converter(wktReader.read(resultado.get("corrigida")));
        } catch (ParseException e) {
            throw new IllegalStateException("WKT inválido do fix_geometry: " + resultado.get("corrigida"), e);
        }
    }

    /**
     * Grava a geometria corrigida (editada à mão ou a sugestão automática). Só aceita se o Oracle
     * a considerar válida, na mesma tolerância do validate. Como o payload corrigido, deixa o
     * registro CORRIGIDO, pronto para revalidar.
     */
    @Transactional
    public RegistroQuarentena saveCorrectedGeometry(Long id, GeometryCorrectionRequest dto) {
        RegistroQuarentena registro = buscarEmAberto(id);

        Geometry geometria;
        try {
            geometria = geoJsonService.ler(objectMapper.writeValueAsString(dto.geometry()));
        } catch (ParseException | JsonProcessingException | RuntimeException e) {
            geometria = null;
        }
        // O JTS lê {"type": "Polygon"} sem coordenadas como polígono vazio.
        if (geometria == null || geometria.isEmpty()) {
            throw new RequisicaoInvalidaException("A geometria corrigida não é um GeoJSON válido.",
                    List.of("geometria"));
        }

        String validacao = validateInOracle(geometria);
        if (!"TRUE".equals(validacao)) {
            throw new RequisicaoInvalidaException(
                    "A geometria corrigida ainda é inválida: " + describeOracleValidation(validacao),
                    List.of("geometria"));
        }

        registro.setGeometriaCorrigida(geometria);
        registro.setStatusAnalise("CORRIGIDO");
        if (dto.justification() != null && !dto.justification().isBlank()) {
            registro.setJustificativa(dto.justification());
        }
        registro.setAnalisadoEm(OffsetDateTime.now());
        return repository.save(registro);
    }

    /** pkg_quality.validate_geometry na geometria em WKT. CLOB: um polígono do CAR passa fácil de 4000 caracteres. */
    private String validateInOracle(Geometry geometria) {
        String wkt = wktWriter.write(geometria);
        return jdbcTemplate.execute((ConnectionCallback<String>) con -> {
            try (CallableStatement cs = con.prepareCall(
                    "BEGIN ? := pkg_quality.validate_geometry(SDO_GEOMETRY(?, " + GeoJsonService.SRID_WGS84 + ")); END;")) {
                Clob clob = con.createClob();
                clob.setString(1, wkt);
                cs.registerOutParameter(1, Types.VARCHAR);
                cs.setClob(2, clob);
                cs.execute();
                return cs.getString(1);
            }
        });
    }

    /** "13349 [Element <1>] [Ring <1>]..." → texto para a tela, com o código do Oracle no fim. */
    static String describeOracleValidation(String validacao) {
        String codigo = validacao.length() >= 5 ? validacao.substring(0, 5) : validacao;
        String texto = switch (codigo) {
            case "13349" -> "o contorno do polígono se cruza";
            case "13356" -> "há vértices repetidos em sequência";
            case "13348" -> "o contorno do polígono não fecha";
            case "13343" -> "o polígono tem menos de 3 vértices";
            case "13350", "13351" -> "anéis do polígono se tocam ou se sobrepõem";
            case "13367" -> "os anéis estão no sentido errado";
            default -> "erro de geometria do Oracle";
        };
        return texto + " (" + validacao + ").";
    }

    private RegistroQuarentena buscarEmAberto(Long id) {
        RegistroQuarentena registro = buscarPorId(id);
        if (STATUS_ENCERRADOS.contains(registro.getStatusAnalise())) {
            throw new QuarantineClosedException(id, registro.getStatusAnalise());
        }
        return registro;
    }

    // O revalidar_registro grava o payload na staging, que exige um objeto JSON
    // (o pkg_quality lê cada campo com JSON_VALUE).
    private void conferirJson(String payload) {
        JsonNode json;
        try {
            json = payload == null ? null : objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            json = null;
        }
        if (json == null || !json.isObject()) {
            throw new RequisicaoInvalidaException("O payload corrigido precisa ser um objeto JSON.",
                    List.of("payloadCorrigido"));
        }
    }

    public QuarentenaMetricasDTO obterMetricas() {
        long pendentes = repository.countByStatusAnalise("PENDENTE");
        long emAnalise = repository.countByStatusAnalise("EM_ANALISE");
        long corrigidos = repository.countByStatusAnalise("CORRIGIDO");
        long descartados = repository.countByStatusAnalise("DESCARTADO");
        long revalidados = repository.countByStatusAnalise("REVALIDADO");
        long substituidos = repository.countByStatusAnalise("SUBSTITUIDO");
        long total = repository.count();

        return new QuarentenaMetricasDTO(pendentes, emAnalise, corrigidos, descartados, revalidados,
                substituidos, total);
    }

    @Transactional
    public RegistroQuarentena iniciarAnalise(Long id) {
        RegistroQuarentena registro = buscarEmAberto(id);
        registro.setStatusAnalise("EM_ANALISE");
        return repository.save(registro);
    }

    @Transactional
    public RegistroQuarentena descartar(Long id, DescarteRequestDTO dto) {
        RegistroQuarentena registro = buscarEmAberto(id);
        registro.setStatusAnalise("DESCARTADO");
        registro.setJustificativa(dto.justificativa());
        registro.setAnalisadoEm(OffsetDateTime.now());
        return repository.save(registro);
    }

    @Transactional
    public RegistroQuarentena corrigir(Long id, CorrecaoRequestDTO dto) {
        RegistroQuarentena registro = buscarEmAberto(id);
        conferirJson(dto.payloadCorrigido());
        registro.setStatusAnalise("CORRIGIDO");
        registro.setPayloadCorrigido(dto.payloadCorrigido());
        if (dto.justificativa() != null) {
            registro.setJustificativa(dto.justificativa());
        }
        registro.setAnalisadoEm(OffsetDateTime.now());
        return repository.save(registro);
    }

    @Transactional
    public RevalidationResponse revalidar(Long id) {
        RegistroQuarentena registro = buscarEmAberto(id);
        RevalidationResponse result = callRevalidation(id);

        // Reprovado: continua na quarentena como está; a tela mostra os erros.
        if (result.approved()) {
            // O pkg_tratada grava na Zona Tratada (o mesmo MERGE da carga) e marca o registro
            // como REVALIDADO, nesta mesma transação. Relê o registro para trazer o que ele gravou.
            gravarNaTratada(id);
            entityManager.refresh(registro);
        }
        return new RevalidationResponse(result.status(), result.errors(), registro);
    }

    /** Chama pkg_tratada.gravar_registro. As recusas do pacote (ORA-20010 a 20014) viram 409. */
    private void gravarNaTratada(Long id) {
        try {
            jdbcTemplate.update("BEGIN pkg_tratada.gravar_registro(?); END;", id);
        } catch (DataAccessException e) {
            SQLException sql = sqlException(e);
            if (sql != null && sql.getErrorCode() >= 20010 && sql.getErrorCode() <= 20014) {
                throw new GravacaoNaoPermitidaException(mensagemDoPacote(sql.getMessage()));
            }
            throw e;
        }
    }

    /** A SQLException com o código do Oracle. Fica no meio da cadeia: o driver ainda põe a dele embaixo. */
    private static SQLException sqlException(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** "ORA-20014: Uma carga mais recente... (e as linhas ORA-06512)" → só o texto escrito pelo pacote. */
    static String mensagemDoPacote(String mensagem) {
        String primeiraLinha = mensagem.lines().findFirst().orElse(mensagem);
        return primeiraLinha.replaceFirst("^ORA-\\d+:\\s*", "").trim();
    }

    /** Chama pkg_quality.revalidar_registro, que só valida e devolve o JSON, sem gravar. */
    private RevalidationResponse callRevalidation(Long id) {
        String json = jdbcTemplate.execute((ConnectionCallback<String>) con -> {
            try (CallableStatement cs = con.prepareCall("{? = call pkg_quality.revalidar_registro(?)}")) {
                cs.registerOutParameter(1, Types.CLOB);
                cs.setLong(2, id);
                cs.execute();
                return cs.getString(1);
            }
        });
        try {
            return objectMapper.readValue(json, RevalidationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON inválido do revalidar_registro: " + json, e);
        }
    }
}