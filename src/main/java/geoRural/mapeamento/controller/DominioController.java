package geoRural.mapeamento.controller;

import geoRural.mapeamento.dto.DominioResponse;
import geoRural.mapeamento.service.DominioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dominios")
public class DominioController {

    private final DominioService dominioService;

    public DominioController(DominioService dominioService) {
        this.dominioService = dominioService;
    }

    @GetMapping
    public List<DominioResponse> listar() {
        return dominioService.listar().stream()
                .map(DominioResponse::de)
                .toList();
    }
}