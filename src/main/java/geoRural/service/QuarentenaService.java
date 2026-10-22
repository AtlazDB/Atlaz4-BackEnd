package geoRural.service;

import geoRural.dto.*;
import geoRural.entity.RegistroQuarentena;
import geoRural.exception.ResourceNotFoundException;
import geoRural.repository.RegistroQuarentenaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class QuarentenaService {

    private final RegistroQuarentenaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public QuarentenaService(RegistroQuarentenaRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Page<RegistroQuarentena> listar(QuarentenaFiltroDTO filtro, Pageable pageable) {
        return repository.buscarComFiltros(
            filtro.tipoErro(), 
            filtro.status(), 
            filtro.texto(), 
            pageable
        );
    }

    public RegistroQuarentena buscarPorId(Long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Registro de quarentena não encontrado: " + id));
    }

    public QuarentenaMetricasDTO obterMetricas() {
        long pendentes = repository.countByStatusAnalise("PENDENTE");
        long emAnalise = repository.countByStatusAnalise("EM_ANALISE");
        long corrigidos = repository.countByStatusAnalise("CORRIGIDO");
        long descartados = repository.countByStatusAnalise("DESCARTADO");
        long revalidados = repository.countByStatusAnalise("REVALIDADO");
        long total = repository.count();

        return new QuarentenaMetricasDTO(pendentes, emAnalise, corrigidos, descartados, revalidados, total);
    }

    @Transactional
    public RegistroQuarentena iniciarAnalise(Long id) {
        RegistroQuarentena registro = buscarPorId(id);
        registro.setStatusAnalise("EM_ANALISE");
        return repository.save(registro);
    }

    @Transactional
    public RegistroQuarentena descartar(Long id, DescarteRequestDTO dto) {
        RegistroQuarentena registro = buscarPorId(id);
        registro.setStatusAnalise("DESCARTADO");
        registro.setJustificativa(dto.justificativa());
        registro.setAnalisadoEm(OffsetDateTime.now());
        return repository.save(registro);
    }

    @Transactional
    public RegistroQuarentena corrigir(Long id, CorrecaoRequestDTO dto) {
        RegistroQuarentena registro = buscarPorId(id);
        registro.setStatusAnalise("CORRIGIDO");
        registro.setPayloadCorrigido(dto.payloadCorrigido());
        if (dto.justificativa() != null) {
            registro.setJustificativa(dto.justificativa());
        }
        registro.setAnalisadoEm(OffsetDateTime.now());
        return repository.save(registro);
    }

    @Transactional
    public RegistroQuarentena revalidar(Long id) {
        RegistroQuarentena registro = buscarPorId(id);

        // Executa a procedure no Oracle PL/SQL: pkg_qualidade.revalidar_registro(p_id)
        jdbcTemplate.update("CALL pkg_qualidade.revalidar_registro(?)", id);

        registro.setStatusAnalise("REVALIDADO");
        registro.setAnalisadoEm(OffsetDateTime.now());
        return repository.save(registro);
    }
}