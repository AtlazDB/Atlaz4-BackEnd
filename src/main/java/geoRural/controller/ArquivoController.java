package geoRural.controller;

import geoRural.dto.ProcessamentoResponse;
import geoRural.service.ArquivoBrutoService;
import geoRural.service.ProcessamentoArquivoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/arquivos")
public class ArquivoController {

    private final ProcessamentoArquivoService processamentoArquivoService;
    private final ArquivoBrutoService arquivoBrutoService;

    public ArquivoController(ProcessamentoArquivoService processamentoArquivoService,
                             ArquivoBrutoService arquivoBrutoService) {
        this.processamentoArquivoService = processamentoArquivoService;
        this.arquivoBrutoService = arquivoBrutoService;
    }

    /**
     * Síncrono por enquanto: a resposta só volta quando o processamento termina.
     * Com o arquivo do Paraná isso vai passar do timeout do navegador; aí vira @Async + 202.
     * O leitor é escolhido pela fonte do arquivo: CAR (imóveis), CAR-RL (Reserva Legal) ou INPE-FOCOS.
     */
    @PostMapping("/{id}/processar")
    public ProcessamentoResponse processar(@PathVariable Long id) {
        return processamentoArquivoService.processar(id);
    }

    /** Só para arquivo REJEITADO que nunca gerou versão de dados; senão 409. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable Long id) {
        arquivoBrutoService.excluirRejeitado(id);
    }
}
