package geoRural.controller;

import geoRural.dto.IndicadorResponse;
import geoRural.dto.IndicadoresImovelResponse;
import geoRural.service.IndicadorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Indicadores de um imóvel: o valor atual de cada um (card da Consulta territorial) e o histórico
 * de cálculos (tela de Rastreabilidade).
 */
@RestController
@RequestMapping("/api/v1/imoveis/{codImovel}/indicadores")
public class IndicadorController {

    private final IndicadorService service;

    public IndicadorController(IndicadorService service) {
        this.service = service;
    }

    @GetMapping
    public IndicadoresImovelResponse buscar(@PathVariable String codImovel) {
        return service.buscarPorImovel(codImovel);
    }

    @GetMapping("/{indicador}/historico")
    public List<IndicadorResponse> historico(@PathVariable String codImovel, @PathVariable String indicador) {
        return service.historico(codImovel, indicador);
    }
}
