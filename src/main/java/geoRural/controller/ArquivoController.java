package geoRural.controller;

import geoRural.dto.ProcessamentoResponse;
import geoRural.service.ArquivoBrutoService;
import geoRural.service.ProcessamentoCarService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/arquivos")
public class ArquivoController {

    private final ProcessamentoCarService processamentoCarService;
    private final ArquivoBrutoService arquivoBrutoService;

    public ArquivoController(ProcessamentoCarService processamentoCarService,
                             ArquivoBrutoService arquivoBrutoService) {
        this.processamentoCarService = processamentoCarService;
        this.arquivoBrutoService = arquivoBrutoService;
    }

    /**
     * Síncrono por enquanto: a resposta só volta quando o processamento termina.
     * Com o arquivo do Paraná isso vai passar do timeout do navegador; aí vira @Async + 202.
     */
    @PostMapping("/{id}/processar")
    public ProcessamentoResponse processar(@PathVariable Long id) {
        return processamentoCarService.processar(id);
    }

    /** Só para arquivo REJEITADO que nunca gerou versão de dados; senão 409. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable Long id) {
        arquivoBrutoService.excluirRejeitado(id);
    }
}
