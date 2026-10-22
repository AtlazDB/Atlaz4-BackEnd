package geoRural.mapeamento.controller;

import geoRural.mapeamento.dto.ReferenciaCruzadaResponse;
import geoRural.mapeamento.service.ReferenciaCruzadaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/referencias-cruzadas")
public class ReferenciaCruzadaController {

    private final ReferenciaCruzadaService referenciaCruzadaService;

    public ReferenciaCruzadaController(ReferenciaCruzadaService referenciaCruzadaService) {
        this.referenciaCruzadaService = referenciaCruzadaService;
    }

    @GetMapping
    public List<ReferenciaCruzadaResponse> listar() {
        return referenciaCruzadaService.listar().stream()
                .map(ReferenciaCruzadaResponse::de)
                .toList();
    }
}