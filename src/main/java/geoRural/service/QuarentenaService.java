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
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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