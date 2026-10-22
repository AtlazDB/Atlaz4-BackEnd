package geoRural.controller;

import geoRural.dto.*;
import geoRural.entity.RegistroQuarentena;
import geoRural.service.QuarentenaService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/quarentena")
public class QuarentenaController {

    private final QuarentenaService service;

    public QuarentenaController(QuarentenaService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Page<RegistroQuarentena>> listar(
            @RequestParam(required = false) String tipoErro,
            @RequestParam(required = false) Long fonteId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String texto,
            Pageable pageable) {

        QuarentenaFiltroDTO filtro = new QuarentenaFiltroDTO(tipoErro, fonteId, status, texto);
        return ResponseEntity.ok(service.listar(filtro, pageable));
    }

    @GetMapping("/metricas")
    public ResponseEntity<QuarentenaMetricasDTO> obterMetricas() {
        return ResponseEntity.ok(service.obterMetricas());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RegistroQuarentena> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(service.buscarPorId(id));
    }

    /** GeoJSON da geometria como chegou; 204 quando ela nem pôde ser lida (GEOMETRIA_MALFORMADA). */
    @GetMapping("/{id}/geometria")
    public ResponseEntity<Map<String, Object>> buscarGeometria(@PathVariable Long id) {
        Map<String, Object> geometria = service.buscarGeometria(id);
        return geometria == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(geometria);
    }

    @PostMapping("/{id}/iniciar-analise")
    public ResponseEntity<RegistroQuarentena> iniciarAnalise(@PathVariable Long id) {
        return ResponseEntity.ok(service.iniciarAnalise(id));
    }

    @PostMapping("/{id}/descartar")
    public ResponseEntity<RegistroQuarentena> descartar(
            @PathVariable Long id, 
            @Valid @RequestBody DescarteRequestDTO dto) {
        return ResponseEntity.ok(service.descartar(id, dto));
    }

    @PostMapping("/{id}/corrigir")
    public ResponseEntity<RegistroQuarentena> corrigir(
            @PathVariable Long id, 
            @Valid @RequestBody CorrecaoRequestDTO dto) {
        return ResponseEntity.ok(service.corrigir(id, dto));
    }

    @PostMapping("/{id}/revalidar")
    public ResponseEntity<RevalidationResponse> revalidar(@PathVariable Long id) {
        return ResponseEntity.ok(service.revalidar(id));
    }
}