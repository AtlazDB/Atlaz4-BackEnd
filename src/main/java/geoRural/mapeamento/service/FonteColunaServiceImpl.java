package geoRural.mapeamento.service;

import geoRural.mapeamento.dto.FonteColunaResponse;
import geoRural.mapeamento.entity.FonteColuna;
import geoRural.mapeamento.entity.RegraCampo;
import geoRural.mapeamento.repository.FonteColunaRepository;
import geoRural.service.FonteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class FonteColunaServiceImpl implements FonteColunaService {

    private final FonteService fonteService;
    private final FonteColunaRepository repository;

    public FonteColunaServiceImpl(FonteService fonteService, FonteColunaRepository repository) {
        this.fonteService = fonteService;
        this.repository = repository;
    }

    @Override
    public List<FonteColunaResponse> listar(Long fonteId) {
        fonteService.buscarPorId(fonteId); // 404 pelo GlobalExceptionHandler se a fonte não existir

        return repository.listarComRegra(fonteId).stream()
                .map(linha -> FonteColunaResponse.de((FonteColuna) linha[0], (RegraCampo) linha[1]))
                .toList();
    }
}