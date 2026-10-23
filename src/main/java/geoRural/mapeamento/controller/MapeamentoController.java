package geoRural.mapeamento.controller;

import geoRural.mapeamento.dto.MapeamentoRequest;
import geoRural.mapeamento.dto.MapeamentoResponse;
import geoRural.mapeamento.service.MapeamentoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fontes/{fonteId}/mapeamentos")
public class MapeamentoController {

    private final MapeamentoService mapeamentoService;

    public MapeamentoController(MapeamentoService mapeamentoService) {
        this.mapeamentoService = mapeamentoService;
    }

    @GetMapping
    public List<MapeamentoResponse> listar(@PathVariable Long fonteId) {
        return mapeamentoService.listar(fonteId).stream()
                .map(MapeamentoResponse::de)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MapeamentoResponse criar(@PathVariable Long fonteId, @RequestBody MapeamentoRequest request) {
        return MapeamentoResponse.de(mapeamentoService.criar(fonteId, request));
    }

    @DeleteMapping("/{mapeamentoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable Long fonteId, @PathVariable Long mapeamentoId) {
        mapeamentoService.remover(fonteId, mapeamentoId);
    }
}