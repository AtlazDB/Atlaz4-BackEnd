package geoRural.controller;

import geoRural.dto.FocosNoMapaResponse;
import geoRural.service.FocoService;
import org.springframework.web.bind.annotation.*;

/** Focos de calor da área visível do mapa, os mesmos que o IFC conta. */
@RestController
@RequestMapping("/api/v1/focos")
public class FocoController {

    private final FocoService service;

    public FocoController(FocoService service) {
        this.service = service;
    }

    @GetMapping
    public FocosNoMapaResponse listar(
            @RequestParam(required = false) Double minLon,
            @RequestParam(required = false) Double minLat,
            @RequestParam(required = false) Double maxLon,
            @RequestParam(required = false) Double maxLat,
            @RequestParam(defaultValue = "2000") int limite) {

        return service.listarNoMapa(minLon, minLat, maxLon, maxLat, limite);
    }
}
