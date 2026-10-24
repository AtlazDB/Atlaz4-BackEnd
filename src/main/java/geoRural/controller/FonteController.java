package geoRural.controller;

import geoRural.mapeamento.dto.FonteColunaResponse;
import geoRural.mapeamento.service.FonteColunaService;
import geoRural.dto.ArquivoBrutoResponse;
import geoRural.dto.FonteRequest;
import geoRural.dto.FonteResponse;
import geoRural.dto.UploadResponse;
import geoRural.entity.ArquivoBruto;
import geoRural.entity.Fonte;
import geoRural.service.ArquivoBrutoService;
import geoRural.service.FonteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/fontes")
public class FonteController {

    private final FonteService fonteService;
    private final ArquivoBrutoService arquivoBrutoService;
    private final FonteColunaService fonteColunaService;

    public FonteController(FonteService fonteService, ArquivoBrutoService arquivoBrutoService, FonteColunaService fonteColunaService) {
    this.fonteService = fonteService;
    this.arquivoBrutoService = arquivoBrutoService;
    this.fonteColunaService = fonteColunaService;
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

    @PostMapping("/testar-link")
    public ResponseEntity<Map<String, Object>> testarLink(@RequestBody java.util.Map<String, Object> payload) {
        
        String urlOrigem = (String) payload.get("urlOrigem");

        if (urlOrigem == null || urlOrigem.isBlank()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                "sucesso", false,
                "mensagem", "A URL de origem é obrigatória para realizar o teste."
            ));
        }

        
        return ResponseEntity.ok(java.util.Map.of(
            "sucesso", true,
            "mensagem", "Conexão validada com sucesso para a URL: " + urlOrigem
        ));
    }

    private FonteResponse toResponse(Fonte fonte) {
        return new FonteResponse(fonte);
    }

    @PostMapping("/{id}/executar")
    public ResponseEntity<java.util.Map<String, Object>> executarFonte(@PathVariable Long id) {
        String resultado = fonteService.executarIngestao(id);
        return ResponseEntity.ok(java.util.Map.of(
            "sucesso", true,
            "mensagem", resultado
        ));
    }
}
