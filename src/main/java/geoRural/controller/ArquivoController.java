package geoRural.controller;

import geoRural.dto.ProcessamentoResponse;
import geoRural.service.ProcessamentoCarService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/arquivos")
public class ArquivoController {

    private final ProcessamentoCarService processamentoCarService;

    public ArquivoController(ProcessamentoCarService processamentoCarService) {
        this.processamentoCarService = processamentoCarService;
    }

    /**
     * Síncrono por enquanto: a resposta só volta quando o processamento termina.
     * Com o arquivo do Paraná isso vai passar do timeout do navegador; aí vira @Async + 202.
     */
    @PostMapping("/{id}/processar")
    public ProcessamentoResponse processar(@PathVariable Long id) {
        return processamentoCarService.processar(id);
    }
}
