package geoRural.controller;

import geoRural.mapeamento.dto.FonteColunaResponse;
import geoRural.mapeamento.service.FonteColunaService;
import geoRural.dto.ArquivoBrutoResponse;
import geoRural.dto.ColetaResponse;
import geoRural.dto.FonteRequest;
import geoRural.dto.FonteResponse;
import geoRural.dto.TesteLinkRequest;
import geoRural.dto.TesteLinkResponse;
import geoRural.dto.UploadResponse;
import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;
import geoRural.service.ArquivoBrutoService;
import geoRural.service.ColetaLinkService;
import geoRural.service.FonteService;
import geoRural.service.TesteLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/fontes")
public class FonteController {

    private final FonteService fonteService;
    private final ArquivoBrutoService arquivoBrutoService;
    private final FonteColunaService fonteColunaService;
    private final ColetaLinkService coletaLinkService;
    private final TesteLinkService testeLinkService;

    public FonteController(FonteService fonteService, ArquivoBrutoService arquivoBrutoService,
                           FonteColunaService fonteColunaService, ColetaLinkService coletaLinkService,
                           TesteLinkService testeLinkService) {
        this.fonteService = fonteService;
        this.arquivoBrutoService = arquivoBrutoService;
        this.fonteColunaService = fonteColunaService;
        this.coletaLinkService = coletaLinkService;
        this.testeLinkService = testeLinkService;
    }

    @GetMapping
    public List<FonteResponse> listar(@RequestParam(required = false) String busca) {
        return fonteService.listar(busca).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}/colunas")
        public List<FonteColunaResponse> listarColunas(@PathVariable Long id) {
    return fonteColunaService.listar(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FonteResponse cadastrar(@RequestBody FonteRequest request) {
        Fonte fonte = fonteService.cadastrar(request);
        return toResponse(fonte);
    }

    @PostMapping("/{id}/arquivos")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResponse enviarArquivo(
            @PathVariable Long id,
            @RequestPart("arquivo") MultipartFile arquivo) throws IOException {

        ArquivoBruto recebido = arquivoBrutoService.receber(id, arquivo);

        return new UploadResponse(
                toResponse(recebido.getFonte()),
                ArquivoBrutoResponse.de(recebido)
        );
    }

    /**
     * "Testar link" do formulário de fonte: pede ao link uma amostra pequena e devolve o status,
     * o total de registros, as colunas e os avisos. Link inválido ou interno responde 400; falha
     * do servidor de origem, 502.
     */
    @PostMapping("/testar-link")
    public TesteLinkResponse testarLink(@RequestBody TesteLinkRequest request) {
        return testeLinkService.testar(request);
    }

    /**
     * "Executar agora" de uma fonte por link: baixa o link para a zona bruta. 201 quando gravou
     * um arquivo novo (RECEBIDO, segue como um upload); 200 quando o conteúdo é igual ao da
     * última coleta (SEM_MUDANCA, nada gravado). Falha do servidor de origem responde 502.
     */
    @PostMapping("/{id}/executar")
    public ResponseEntity<ColetaResponse> executar(@PathVariable Long id) {
        ColetaResponse resultado = coletaLinkService.executar(id);
        HttpStatus status = "RECEBIDO".equals(resultado.resultado()) ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(resultado);
    }

    private FonteResponse toResponse(Fonte fonte) {
        return FonteResponse.de(fonte, arquivoBrutoService.listarPorFonte(fonte.getId()));
    }
}
